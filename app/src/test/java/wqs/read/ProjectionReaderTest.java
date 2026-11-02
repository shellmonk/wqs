package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;

@SpringBootTest
class ProjectionReaderTest extends ReadPropertyBase {

    private static final Level0Filter ALL = new Level0Filter(1L, false);

    @BeforeEach
    void clean() {
        reset();
    }

    /**
     * T5 ruling (spec 7.5 bounded cost): DAY counters are read per contiguous run of DAY segments,
     * so a whole middle month's daily rows are never fetched and discarded.
     */
    @Test
    void day_counters_are_read_per_run_not_across_whole_months() {
        applyBatches(List.of(
                item("j", "2034-01-20T10:00:00Z"),
                item("f1", "2034-02-01T10:00:00Z"),
                item("f15", "2034-02-15T10:00:00Z"),
                item("f28", "2034-02-28T10:00:00Z"),
                item("m", "2034-03-05T10:00:00Z")));
        RowCountingCounterReader counting = new RowCountingCounterReader();
        ProjectionReader probe = probeReader(counting);
        Instant from = Instant.parse("2034-01-10T00:00:00Z");
        Instant to = Instant.parse("2034-03-10T00:00:00Z");

        SegmentCounts sc = readerDsl.transactionResult(cfg ->
                probe.segmentCounts(DSL.using(cfg), webQuery, ALL, from, to));

        assertThat(sc.total()).isEqualTo(5).isEqualTo(oracle.count(ViewKind.WEB, ALL, from, to));
        // Feb's month row + the Jan 20 and Mar 5 day rows.
        assertThat(sc.counterRows()).isEqualTo(3);
        assertThat(counting.dayKeys).containsExactlyInAnyOrder(
                LocalDate.parse("2034-01-20"), LocalDate.parse("2034-03-05"));
        assertThat(counting.dayCalls).isEqualTo(2);
    }

    // ------------------------------------------------------------------ readLevel0 examples

    /** Spec 7.9 / review focus 3: overflowing or huge offsets are "beyond the end", never an error. */
    @Test
    void far_beyond_last_page_is_empty_not_an_error() {
        applyBatches(fiveItems());
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");
        long[][] beyond = {
            {Long.MAX_VALUE / 2, Integer.MAX_VALUE},   // (page-1)*pageSize overflows a long
            {Integer.MAX_VALUE, Integer.MAX_VALUE},    // fits a long, far past the end
            {2, Long.MAX_VALUE},                       // offset 1 * Long.MAX_VALUE fits, past the end
            {3, Long.MAX_VALUE},                       // 2 * Long.MAX_VALUE overflows a long
            {Long.MAX_VALUE, Long.MAX_VALUE},
        };
        for (long[] pp : beyond) {
            ProjectionPage page = reader.readLevel0(webQuery, new Level0Request(ALL, from, to, pp[0], pp[1]));
            assertThat(page.items()).as("page=%d size=%d", pp[0], pp[1]).isEmpty();
            assertThat(page.totalItems()).isEqualTo(5);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.page()).isEqualTo(pp[0]);
            assertThat(page.pageSize()).isEqualTo(pp[1]);
        }
        // Page 1 with a page size past int range still returns everything (limit cast is guarded).
        for (long size : new long[] {Integer.MAX_VALUE, Long.MAX_VALUE}) {
            assertThat(reader.readLevel0(webQuery, new Level0Request(ALL, from, to, 1, size)).items())
                    .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 0, Integer.MAX_VALUE))
                    .hasSize(5);
        }
        ProjectionPage empty = reader.readLevel0(webQuery,
                new Level0Request(new Level0Filter(4L, false), from, to, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalItems()).isZero();
        assertThat(empty.totalPages()).isZero();
    }

    /** Review focus 5: months with no partitions at all are just empty. */
    @Test
    void range_far_outside_provisioned_partitions_is_fine() {
        applyBatches(fiveItems());
        Instant from = Instant.parse("1990-01-01T00:00:00Z");
        Instant to = Instant.parse("2100-01-01T00:00:00Z");
        for (ViewKind v : ViewKind.values()) {
            ProjectionPage page = reader.readLevel0(queryFor(v), new Level0Request(ALL, from, to, 1, 3));
            assertThat(page.totalItems()).isEqualTo(5).isEqualTo(oracle.count(v, ALL, from, to));
            assertThat(page.totalPages()).isEqualTo(2);
            assertThat(page.aggregationLevel()).isZero();
            assertThat(page.items()).isEqualTo(oracle.page(v, ALL, from, to, 0, 3));
            assertThat(reader.readLevel0(queryFor(v), new Level0Request(ALL, from, to, 2, 3)).items())
                    .isEqualTo(oracle.page(v, ALL, from, to, 3, 3));
        }
    }

    /** Spec 7.9 / D7: the ceiling ships disabled. */
    @Test
    void default_context_does_not_clamp_page_size() {
        applyBatches(fiveItems());
        ProjectionPage page = reader.readLevel0(webQuery, new Level0Request(ALL,
                Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-03-01T00:00:00Z"), 1, 10));
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.items()).hasSize(5);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    /** Spec 7.9 / D7: when enabled, the ceiling clamps and the response reports the effective size. */
    @Nested
    @TestPropertySource(properties = "wqs.read.max-page-size=3")
    class PageSizeCeiling {

        @Autowired ProjectionReader clamped;

        @Test
        void page_size_ceiling_clamps_when_enabled() {
            applyBatches(fiveItems());
            Instant from = Instant.parse("2034-01-01T00:00:00Z");
            Instant to = Instant.parse("2034-03-01T00:00:00Z");
            ProjectionPage page = clamped.readLevel0(webQuery, new Level0Request(ALL, from, to, 1, 10));
            assertThat(page.pageSize()).isEqualTo(3);
            assertThat(page.items()).hasSize(3).isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 0, 3));
            assertThat(page.totalPages()).isEqualTo(2);
            // Page 2 is computed with the effective size, not the requested one.
            assertThat(clamped.readLevel0(webQuery, new Level0Request(ALL, from, to, 2, 10)).items())
                    .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 3, 3)).hasSize(2);
            // A request under the ceiling is untouched.
            assertThat(clamped.readLevel0(webQuery, new Level0Request(ALL, from, to, 1, 2)).pageSize())
                    .isEqualTo(2);
        }
    }

    /**
     * Spec 11.2. Feb has 3 items, Jan 2; page 2 of size 2 starts in the Feb month segment with
     * residual 2 and returns Feb's 3rd item and Jan's 1st -- two distinct months touched.
     */
    @Test
    void metrics_record_residual_and_counter_rows() {
        applyBatches(fiveItems());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectionReader probe = new ProjectionReader(
                readerDsl, new CounterReader(), new ReadProperties(null), new ReadMetrics(registry));
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");

        ProjectionPage page = probe.readLevel0(webQuery, new Level0Request(ALL, from, to, 2, 2));

        assertThat(page.items()).extracting(ItemEntry::sourceId).containsExactly("f1", "j2");
        assertThat(registry.get("wqs.read.residual.offset").summary().max()).isEqualTo(2);
        assertThat(registry.get("wqs.read.counter.rows").summary().max()).isEqualTo(2);
        assertThat(registry.get("wqs.read.partitions.touched").tag("aggregationLevel", "0").summary().max())
                .isEqualTo(2);
        assertThat(registry.get("wqs.read.pagesize").tag("endpoint", "web").summary().max()).isEqualTo(2);
        assertThat(registry.get("wqs.read.totalitems").tag("endpoint", "web").summary().max()).isEqualTo(5);

        // An empty page touches no partitions and walks no segment.
        probe.readLevel0(ccQuery, new Level0Request(ALL, from, to, 9, 2));
        assertThat(registry.get("wqs.read.partitions.touched").tag("aggregationLevel", "0").summary().count()).isEqualTo(2);
        assertThat(registry.get("wqs.read.partitions.touched").tag("aggregationLevel", "0").summary().totalAmount()).isEqualTo(2);
        assertThat(registry.get("wqs.read.residual.offset").summary().count()).isEqualTo(1);
        assertThat(registry.get("wqs.read.totalitems").tag("endpoint", "cc").summary().max()).isEqualTo(5);
    }

    /**
     * Spec 7.6: the walk skips whole segments by their counts, so the key query starts at the
     * target segment's end with only the residual. Page 3 of size 2 is offset 4; Feb holds 3, so
     * the walk lands in Jan with residual 1 -- naive OFFSET would query [from, to) with offset 4.
     */
    @Test
    void walk_lands_in_the_target_segment_with_a_small_residual() {
        applyBatches(fiveItems());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectionReader probe = new ProjectionReader(
                readerDsl, new CounterReader(), new ReadProperties(null), new ReadMetrics(registry));
        RecordingLevel0Query recording = new RecordingLevel0Query(webQuery);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");

        ProjectionPage page = probe.readLevel0(recording, new Level0Request(ALL, from, to, 3, 2));

        assertThat(recording.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                from, Instant.parse("2034-02-01T00:00:00Z"), 1, 1));
        assertThat(registry.get("wqs.read.residual.offset").summary().max()).isEqualTo(1);
        assertThat(page.items()).extracting(ItemEntry::sourceId).containsExactly("j1");
    }

    /** Zero-count segments are walked past: Feb is empty, so offset 3 skips Mar (3) and Feb (0). */
    @Test
    void walk_skips_an_empty_middle_segment() {
        applyBatches(List.of(
                item("j1", "2034-01-05T10:00:00Z"),
                item("j2", "2034-01-25T10:00:00Z"),
                item("m1", "2034-03-02T10:00:00Z"),
                item("m2", "2034-03-14T10:00:00Z"),
                item("m3", "2034-03-27T10:00:00Z")));
        RecordingLevel0Query recording = new RecordingLevel0Query(webQuery);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-04-01T00:00:00Z");

        ProjectionPage page = reader.readLevel0(recording, new Level0Request(ALL, from, to, 2, 3));

        assertThat(recording.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                from, Instant.parse("2034-02-01T00:00:00Z"), 0, 2));
        assertThat(page.items()).extracting(ItemEntry::sourceId).containsExactly("j2", "j1");
        assertThat(page.items()).isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 3, 3));
    }

    /**
     * Spec 7.5 / P-3 within a request: totals and items come from ONE snapshot. A writer commit
     * (through the real writer, so counters move too) lands mid-read -- on the reader's first
     * Level0Query call, after the counter rows were read -- inside the range. Neither the total
     * nor the page may see it; the next read sees both. Two placements: in the live remainder
     * (a leak shows in the live count) and in a counter-covered month (counters were already
     * read, so a leak shows only in the items).
     */
    @Test
    void a_read_sees_one_snapshot_despite_a_concurrent_commit() {
        assertOneSnapshot("2034-03-01T06:00:00Z", List.of("late", "f3", "f2", "f1", "j2", "j1"));
        reset();
        assertOneSnapshot("2034-02-20T10:00:00Z", List.of("f3", "late", "f2", "f1", "j2", "j1"));
    }

    private void assertOneSnapshot(String lateAt, List<String> afterOrder) {
        applyBatches(fiveItems());
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T12:00:00Z");
        Level0Request req = new Level0Request(ALL, from, to, 1, 10);
        ProjectionPage before = reader.readLevel0(webQuery, req);
        assertThat(before.totalItems()).isEqualTo(5);

        CommitOnFirstCall probe = new CommitOnFirstCall(webQuery, () -> writer.apply(new wqs.projection.ProjectionBatch(
                "wallet-transactions", List.of(item("late", lateAt)), 100L)));
        ProjectionPage during = reader.readLevel0(probe, req);

        assertThat(probe.fired).as("the commit happened inside the read").isTrue();
        assertThat(during.totalItems()).as("total, late item at %s", lateAt).isEqualTo(5);
        assertThat(during.items()).as("items, late item at %s", lateAt).isEqualTo(before.items());
        ProjectionPage after = reader.readLevel0(webQuery, req);
        assertThat(after.totalItems()).isEqualTo(6);
        assertThat(after.items()).extracting(ItemEntry::sourceId).containsExactlyElementsOf(afterOrder);
    }

    /** Delegate that runs {@code commit} (a writer-side transaction) before its first call of any kind. */
    static final class CommitOnFirstCall implements Level0Query {
        private final Level0Query delegate;
        private final Runnable commit;
        boolean fired;

        CommitOnFirstCall(Level0Query delegate, Runnable commit) {
            this.delegate = delegate;
            this.commit = commit;
        }

        private void once() {
            if (!fired) {
                fired = true;
                commit.run();
            }
        }

        @Override
        public ViewKind view() {
            return delegate.view();
        }

        @Override
        public long count(DSLContext tx, Level0Filter f, Instant lo, Instant hi) {
            once();
            return delegate.count(tx, f, lo, hi);
        }

        @Override
        public List<ItemKey> keys(DSLContext tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
            once();
            return delegate.keys(tx, f, lo, hi, offset, limit);
        }

        @Override
        public List<ItemEntry> fetch(DSLContext tx, long userId, List<ItemKey> keys) {
            once();
            return delegate.fetch(tx, userId, keys);
        }
    }

    /** 3 items in Feb 2034, 2 in Jan 2034, all user 1. */
    private static List<ProjectionItem> fiveItems() {
        return List.of(
                item("j1", "2034-01-05T10:00:00Z"),
                item("j2", "2034-01-25T10:00:00Z"),
                item("f1", "2034-02-02T10:00:00Z"),
                item("f2", "2034-02-14T10:00:00Z"),
                item("f3", "2034-02-27T10:00:00Z"));
    }

    /** Builds a reader around a test counter reader, with no ceiling and a private registry. */
    private ProjectionReader probeReader(CounterReader counters) {
        return new ProjectionReader(
                readerDsl, counters, new ReadProperties(null), new ReadMetrics(new SimpleMeterRegistry()));
    }

    /** Records every day row {@link CounterReader#days} returns, without a mocking framework. */
    static final class RowCountingCounterReader extends CounterReader {
        final List<LocalDate> dayKeys = new ArrayList<>();
        int dayCalls;

        @Override
        public Map<LocalDate, Long> days(
                DSLContext tx, ViewKind v, Level0Filter f, LocalDate firstPeriod, LocalDate lastPeriod) {
            Map<LocalDate, Long> rows = super.days(tx, v, f, firstPeriod, lastPeriod);
            dayKeys.addAll(rows.keySet());
            dayCalls++;
            return rows;
        }
    }

    static ProjectionItem item(String id, String at) {
        return new ProjectionItem(1L, SourceKind.TRANSACTION, id, Instant.parse(at), "P11", "keno", null, null,
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
