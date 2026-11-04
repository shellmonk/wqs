package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.read.web.WebTransactionMapper;
import wqs.read.web.WebLevel0Query;

@SpringBootTest
class ProjectionReaderTest extends ReadPropertyBase {

    private static final Level0Filter ALL = new Level0Filter(1L, false);

    @Autowired WebTransactionMapper webMapper;

    @BeforeEach
    void clean() {
        reset();
    }

    /**
     * Plan D5 (spec 7.5 bounded cost): the months a range touches -- its run of whole months and its
     * two partial edge months -- are read in ONE count query that returns only the months holding rows.
     */
    @Test
    void month_counts_are_read_in_one_query_over_the_range() {
        applyBatches(List.of(
                transaction("j", "2034-01-20T10:00:00Z"),
                transaction("f1", "2034-02-01T10:00:00Z"),
                transaction("f15", "2034-02-15T10:00:00Z"),
                transaction("f28", "2034-02-28T10:00:00Z"),
                transaction("m", "2034-03-05T10:00:00Z")));
        RowCountingCounterReader counting = new RowCountingCounterReader();
        ProjectionReader probe = probeReader(counting);
        Instant from = Instant.parse("2034-01-10T00:00:00Z");
        Instant to = Instant.parse("2034-03-10T00:00:00Z");

        SegmentCounts sc = readerTx.execute(status ->
                probe.segmentCounts(readerJdbc, webQuery, ALL, from, to));

        assertThat(sc.total()).isEqualTo(5).isEqualTo(oracle.count(ViewKind.WEB, ALL, from, to));
        // The one whole month (Feb) is a count; Jan and Mar are partial, live-counted.
        assertThat(sc.counterRows()).isEqualTo(1);
        assertThat(counting.monthKeys).containsExactlyInAnyOrder(
                YearMonth.of(2034, 1), YearMonth.of(2034, 2), YearMonth.of(2034, 3));
        assertThat(counting.monthCalls).isEqualTo(1);
    }

    /**
     * Plan D5: a multi-year range (over 1,000 days of whole months) is totalled from its month count
     * rows plus a live count of the two partial edge months, and equals a live COUNT -- at both page
     * units, in both views, with and without excludeCancelled. Rows sit on both sides of each
     * bound inside the partial days, sessions span midnight, payments are /cc only, and some
     * transactions are cancelled. (2037-2040: years no other test drops partitions of.)
     */
    @Test
    void totals_over_more_than_1000_days_equal_a_live_count() {
        Instant from = Instant.parse("2037-03-10T13:00:00Z");
        Instant to = Instant.parse("2040-06-20T05:00:00Z");
        List<ProjectionTransaction> d = new ArrayList<>(List.of(
                transaction("lo-out", "2037-03-10T12:59:59Z"),
                transaction("lo-in", "2037-03-10T13:00:00Z"),
                transaction("hi-in", "2040-06-20T04:59:59Z"),
                transaction("hi-out", "2040-06-20T05:00:00Z")));
        Instant t = Instant.parse("2037-03-11T08:00:00Z");
        for (int n = 0; t.isBefore(to); n++, t = t.plus(java.time.Duration.ofHours(4 * 24 + 7))) {
            if (n % 3 == 0) {
                d.add(sessioned("s" + n + "a", t.toString(), "b" + n));
                d.add(sessioned("s" + n + "b", t.plus(java.time.Duration.ofHours(20)).toString(), "b" + n));
            } else {
                d.add(transaction("t" + n, t.toString()));
            }
            if (n % 5 == 0) {
                d.add(new ProjectionTransaction(1L, SourceKind.PAYMENT, "pay" + n, t.plusSeconds(60), null, null,
                        null, null, TxTypes.NEUTRAL, new BigDecimal("3.0000"), "EUR", null, null, null));
            }
            if (n % 7 == 1) {
                d.add(new ProjectionTransaction(1L, SourceKind.TRANSACTION, "t" + n + "-x", t.plusSeconds(120), null,
                        null, null, null, TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, "t" + n, null));
            }
        }
        applyBatches(d);
        List<Segment> segments = RangeDecomposition.newestFirst(from, to);
        assertThat(segments).extracting(Segment::tier)
                .containsExactly(Segment.Tier.LIVE, Segment.Tier.MONTH, Segment.Tier.LIVE);
        assertThat(java.time.Duration.between(segments.get(1).start(), segments.get(1).end()).toDays())
                .as("whole months from counts").isGreaterThanOrEqualTo(1_000);

        for (ViewKind v : ViewKind.values()) {
            for (boolean excl : new boolean[] {false, true}) {
                Level0Filter f = new Level0Filter(1L, excl);
                HighLevelFilter h = new HighLevelFilter(1L, excl);
                String as = v + " excl=" + excl;
                long live = oracle.count(v, f, from, to);
                assertThat(live).as(as).isGreaterThan(250);
                assertThat(reader.total(queryFor(v), f, from, to)).as(as).isEqualTo(live);
                assertThat(reader.total(highLevelQueryFor(v), h, from, to)).as(as + " high level")
                        .isEqualTo(oracle.highLevelCount(v, h, from, to));
                // The page in the middle of the range lands where naive OFFSET does.
                long mid = live / 2;
                assertThat(reader.readLevel0(queryFor(v), new Level0Request(f, from, to, mid / 7 + 1, 7)).transactions())
                        .as(as).isEqualTo(oracle.page(v, f, from, to, (mid / 7) * 7, 7));
            }
        }
    }

    /**
     * Plan D5: filtered totals are sums of the exact combinations the filter selects -- product types x
     * transaction types x excludeCancelled, through each endpoint's policy (payments are /cc only) -- plus
     * live counts of the partial edge months, and equal a live COUNT at both units.
     */
    @Test
    void filtered_totals_equal_a_live_count_at_both_units_and_both_policies() {
        List<ProjectionTransaction> d = new ArrayList<>();
        String[] products = {"keno", "lottery", null};
        String[] types = {TxTypes.PLAY, TxTypes.WIN, TxTypes.NEUTRAL};
        SourceKind[] sources = {SourceKind.TRANSACTION, SourceKind.CLAIMABLE_PRIZE, SourceKind.PAYMENT};
        Instant t = Instant.parse("2033-12-02T07:00:00Z");
        for (int n = 0; n < 90; n++, t = t.plus(java.time.Duration.ofHours(29))) {
            String product = products[n % 3];
            SourceKind source = sources[(n / 3) % 3];
            String batch = n % 4 == 0 && source != SourceKind.PAYMENT ? "fb" + (n / 8) : null;
            d.add(new ProjectionTransaction(1L, source, "r" + n, t, product == null ? null : "P-" + product, product,
                    batch, null, types[(n / 2) % 3], new BigDecimal("1.0000"), "EUR", null, null, null));
            if (n % 7 == 3) {
                d.add(new ProjectionTransaction(1L, source, "r" + n + "-x", t.plusSeconds(30), null, null, null, null,
                        TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, "r" + n, null));
            }
        }
        applyBatches(d);
        Instant from = Instant.parse("2033-12-10T05:00:00Z");
        Instant to = Instant.parse("2034-03-15T17:00:00Z");
        List<java.util.Set<String>> productFilters = List.of(java.util.Set.of(), java.util.Set.of("keno"),
                java.util.Set.of("keno", "lottery"), java.util.Set.of("absent"));
        List<java.util.Set<String>> typeFilters = List.of(java.util.Set.of(), java.util.Set.of(TxTypes.WIN),
                java.util.Set.of(TxTypes.PLAY, TxTypes.CANCEL));
        int checked = 0;
        for (ViewKind v : ViewKind.values()) {
            for (boolean excl : new boolean[] {false, true}) {
                for (java.util.Set<String> p : productFilters) {
                    HighLevelFilter h = new HighLevelFilter(1L, p, excl);
                    assertThat(reader.total(highLevelQueryFor(v), h, from, to)).as(v + " " + h)
                            .isEqualTo(oracle.highLevelCount(v, h, from, to));
                    for (java.util.Set<String> ty : typeFilters) {
                        Level0Filter f = new Level0Filter(1L, p, ty, excl);
                        long live = oracle.count(v, f, from, to);
                        assertThat(reader.total(queryFor(v), f, from, to)).as(v + " " + f).isEqualTo(live);
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(48);
        assertThat(oracle.count(ViewKind.CC, ALL, from, to)).as("payments make /cc differ")
                .isGreaterThan(oracle.count(ViewKind.WEB, ALL, from, to));
    }

    /**
     * Final-review I1: a caller can send the whole clamped domain [1970, 2200), about 2,760 months.
     * The segments the walk sees are bounded by the user's ACTIVE months -- one per active month, one
     * per gap between them, plus the ends -- not by the range's length, at both page units.
     */
    @Test
    void a_full_domain_range_builds_segments_per_active_month_only() {
        applyBatches(fiveTransactions());
        Instant from = wqs.domain.DataDomain.START;
        Instant to = wqs.domain.DataDomain.END;
        int activeMonths = 2;

        SegmentCounts level0 = readerTx.execute(status -> reader.segmentCounts(readerJdbc, webQuery, ALL, from, to));
        SegmentCounts high = readerTx.execute(status -> reader.segmentCounts(readerJdbc, webHighLevelQuery, HL_ALL, from, to));

        for (SegmentCounts sc : List.of(level0, high)) {
            assertThat(sc.segments()).hasSizeLessThanOrEqualTo(2 * activeMonths + 3);
            assertThat(sc.counterRows()).isEqualTo(activeMonths);
            assertThat(sc.total()).isEqualTo(5);
            // The segments still tile the range, newest first.
            assertThat(sc.segments().get(0).end()).isEqualTo(to);
            assertThat(sc.segments().get(sc.segments().size() - 1).start()).isEqualTo(from);
            for (int k = 1; k < sc.segments().size(); k++) {
                assertThat(sc.segments().get(k).end()).isEqualTo(sc.segments().get(k - 1).start());
            }
        }
        // And the walk over them still lands on naive OFFSET.
        for (long p = 1; p <= 3; p++) {
            assertThat(reader.readLevel0(webQuery, new Level0Request(ALL, from, to, p, 2)).transactions())
                    .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, (p - 1) * 2, 2));
        }
    }

    // ------------------------------------------------------------------ readLevel0 examples

    /** Spec 7.9 / review focus 3: overflowing or huge offsets are "beyond the end", never an error. */
    @Test
    void far_beyond_last_page_is_empty_not_an_error() {
        applyBatches(fiveTransactions());
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
            assertThat(page.transactions()).as("page=%d size=%d", pp[0], pp[1]).isEmpty();
            assertThat(page.totalItems()).isEqualTo(5);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.page()).isEqualTo(pp[0]);
            assertThat(page.pageSize()).isEqualTo(pp[1]);
        }
        // Page 1 with a page size past int range still returns everything (limit cast is guarded).
        for (long size : new long[] {Integer.MAX_VALUE, Long.MAX_VALUE}) {
            assertThat(reader.readLevel0(webQuery, new Level0Request(ALL, from, to, 1, size)).transactions())
                    .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 0, Integer.MAX_VALUE))
                    .hasSize(5);
        }
        ProjectionPage empty = reader.readLevel0(webQuery,
                new Level0Request(new Level0Filter(4L, false), from, to, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThat(empty.transactions()).isEmpty();
        assertThat(empty.totalItems()).isZero();
        assertThat(empty.totalPages()).isZero();
    }

    /** Review focus 5: months with no partitions at all are just empty. */
    @Test
    void range_far_outside_provisioned_partitions_is_fine() {
        applyBatches(fiveTransactions());
        Instant from = Instant.parse("1990-01-01T00:00:00Z");
        Instant to = Instant.parse("2100-01-01T00:00:00Z");
        for (ViewKind v : ViewKind.values()) {
            ProjectionPage page = reader.readLevel0(queryFor(v), new Level0Request(ALL, from, to, 1, 3));
            assertThat(page.totalItems()).isEqualTo(5).isEqualTo(oracle.count(v, ALL, from, to));
            assertThat(page.totalPages()).isEqualTo(2);
            assertThat(page.aggregationLevel()).isZero();
            assertThat(page.transactions()).isEqualTo(oracle.page(v, ALL, from, to, 0, 3));
            assertThat(reader.readLevel0(queryFor(v), new Level0Request(ALL, from, to, 2, 3)).transactions())
                    .isEqualTo(oracle.page(v, ALL, from, to, 3, 3));
        }
    }

    /** Spec 7.9 / D7: the ceiling ships disabled. */
    @Test
    void default_context_does_not_clamp_page_size() {
        applyBatches(fiveTransactions());
        ProjectionPage page = reader.readLevel0(webQuery, new Level0Request(ALL,
                Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-03-01T00:00:00Z"), 1, 10));
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.transactions()).hasSize(5);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    /** Spec 7.9 / D7: when enabled, the ceiling clamps and the response reports the effective size. */
    @Nested
    @TestPropertySource(properties = "wqs.read.max-page-size=3")
    class PageSizeCeiling {

        @Autowired ProjectionReader clamped;

        @Test
        void page_size_ceiling_clamps_when_enabled() {
            applyBatches(fiveTransactions());
            Instant from = Instant.parse("2034-01-01T00:00:00Z");
            Instant to = Instant.parse("2034-03-01T00:00:00Z");
            ProjectionPage page = clamped.readLevel0(webQuery, new Level0Request(ALL, from, to, 1, 10));
            assertThat(page.pageSize()).isEqualTo(3);
            assertThat(page.transactions()).hasSize(3).isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 0, 3));
            assertThat(page.totalPages()).isEqualTo(2);
            // Page 2 is computed with the effective size, not the requested one.
            assertThat(clamped.readLevel0(webQuery, new Level0Request(ALL, from, to, 2, 10)).transactions())
                    .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 3, 3)).hasSize(2);
            // A request under the ceiling is untouched.
            assertThat(clamped.readLevel0(webQuery, new Level0Request(ALL, from, to, 1, 2)).pageSize())
                    .isEqualTo(2);
        }

        @Test
        void page_size_ceiling_clamps_high_level_pages_too() {
            applyBatches(fiveTransactions());
            Instant from = Instant.parse("2034-01-01T00:00:00Z");
            Instant to = Instant.parse("2034-03-01T00:00:00Z");
            assertThat(clamped.effectivePageSize(10)).isEqualTo(3);
            assertThat(clamped.effectivePageSize(2)).isEqualTo(2);
            assertThat(clamped.readHighLevel(hlRequest(ViewKind.WEB, from, to, 1, 10), (tx, k) -> k).entries())
                    .isEqualTo(oracle.highLevelPage(ViewKind.WEB, HL_ALL, from, to, 0, 3));
            assertThat(clamped.readHighLevel(hlRequest(ViewKind.WEB, from, to, 2, 10), (tx, k) -> k).entries())
                    .isEqualTo(oracle.highLevelPage(ViewKind.WEB, HL_ALL, from, to, 3, 3)).hasSize(2);
        }
    }

    /**
     * Spec 11.2. Mar, Feb and Jan hold one transaction each, Dec two; page 2 of size 2 skips the Mar
     * and Feb months, starts in the Jan month segment with residual 0 and returns Jan's transaction and
     * Dec's newer one -- two distinct months touched, four month count rows read.
     */
    @Test
    void metrics_record_residual_and_counter_rows() {
        applyBatches(fiveMonthlyTransactions());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectionReader probe = new ProjectionReader(
                readerJdbc, readerTx, new CounterReader(), new ReadProperties(null), new ReadMetrics(registry), highLevelQueries());
        Instant from = MONTHLY_FROM;
        Instant to = MONTHLY_TO;

        ProjectionPage page = probe.readLevel0(webQuery, new Level0Request(ALL, from, to, 2, 2));

        assertThat(page.transactions()).extracting(TransactionEntry::sourceId).containsExactly("j1", "d2");
        assertThat(registry.get("wqs.read.residual.offset").summary().max()).isZero();
        assertThat(registry.get("wqs.read.counter.rows").summary().max()).isEqualTo(4);
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
     * target segment's end with only the residual, down to the start of the segment holding the
     * page's last row (plan D5). Page 2 of size 3 is offset 3; the three newer months hold one
     * transaction each, so the walk lands in the December month with residual 0 and the two-row page
     * ends there too -- naive OFFSET would query [from, to) with offset 3.
     */
    @Test
    void walk_lands_in_the_target_segment_with_a_small_residual() {
        applyBatches(fiveMonthlyTransactions());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectionReader probe = new ProjectionReader(
                readerJdbc, readerTx, new CounterReader(), new ReadProperties(null), new ReadMetrics(registry), highLevelQueries());
        RecordingLevel0Query recording = new RecordingLevel0Query(webQuery);
        Instant from = MONTHLY_FROM;
        Instant to = MONTHLY_TO;

        ProjectionPage page = probe.readLevel0(recording, new Level0Request(ALL, from, to, 2, 3));

        assertThat(recording.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                Instant.parse("2033-12-01T00:00:00Z"), Instant.parse("2034-01-01T00:00:00Z"), 0, 2));
        assertThat(recording.countCalls).as("whole months need no live count").isZero();
        assertThat(registry.get("wqs.read.residual.offset").summary().max()).isZero();
        assertThat(page.transactions()).extracting(TransactionEntry::sourceId).containsExactly("d2", "d1");
    }

    /**
     * Zero-count segments are walked past: Feb is empty, so offset 3 skips Mar (3) and Feb (0); the
     * page's two rows are January's, the key query's month (plan D5).
     */
    @Test
    void walk_skips_an_empty_middle_segment() {
        applyBatches(List.of(
                transaction("j1", "2034-01-05T10:00:00Z"),
                transaction("j2", "2034-01-25T10:00:00Z"),
                transaction("m1", "2034-03-02T10:00:00Z"),
                transaction("m2", "2034-03-14T10:00:00Z"),
                transaction("m3", "2034-03-27T10:00:00Z")));
        RecordingLevel0Query recording = new RecordingLevel0Query(webQuery);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-04-01T00:00:00Z");

        ProjectionPage page = reader.readLevel0(recording, new Level0Request(ALL, from, to, 2, 3));

        assertThat(recording.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-02-01T00:00:00Z"), 0, 2));
        assertThat(page.transactions()).extracting(TransactionEntry::sourceId).containsExactly("j2", "j1");
        assertThat(page.transactions()).isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 3, 3));
    }

    /**
     * Plan D5 edge cases: the key query's lower bound is the start of the segment holding the
     * page's last row, clamped to {@code from}. (1) A page ending in the LIVE upper edge month is
     * bounded below by that partial month's start. (2) Rows tied on one instant across the page end:
     * the bound is a month boundary, so the whole tie group is inside it and the page equals naive
     * OFFSET. (3) The last page ends in the LIVE lower edge month, whose start is {@code from}: the
     * bound never goes below it, and a row before {@code from} in that same month is not returned.
     */
    @Test
    void the_key_query_is_bounded_by_the_months_the_page_covers() {
        applyBatches(List.of(
                transaction("before-from", "2033-12-05T07:00:00Z"),
                transaction("lo-live", "2033-12-05T10:00:00Z"),
                transaction("tie-a", "2034-01-20T10:00:00Z"),
                transaction("tie-b", "2034-01-20T10:00:00Z"),
                transaction("tie-c", "2034-01-20T10:00:00Z"),
                transaction("tie-d", "2034-01-20T10:00:00Z"),
                transaction("day21", "2034-01-21T10:00:00Z"),
                transaction("hi-1", "2034-02-10T09:00:00Z"),
                transaction("hi-2", "2034-02-10T10:00:00Z"),
                transaction("hi-3", "2034-02-10T11:00:00Z")));
        Instant from = Instant.parse("2033-12-05T08:00:00Z");
        Instant to = Instant.parse("2034-02-10T12:00:00Z");

        // (1) Page 1 of 2: both rows in the upper LIVE segment [Feb 1 00:00, to).
        RecordingLevel0Query upper = new RecordingLevel0Query(webQuery);
        assertThat(reader.readLevel0(upper, new Level0Request(ALL, from, to, 1, 2)).transactions())
                .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 0, 2));
        assertThat(upper.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                Instant.parse("2034-02-01T00:00:00Z"), to, 0, 2));

        // (2) Size 3, page 2 of 3: day21 and two of the four tied rows -- the tie group straddles
        // the page end, and the bound (Jan 1 00:00) holds all of it.
        RecordingLevel0Query ties = new RecordingLevel0Query(webQuery);
        assertThat(reader.readLevel0(ties, new Level0Request(ALL, from, to, 2, 3)).transactions())
                .isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 3, 3))
                .extracting(TransactionEntry::sourceId).startsWith("day21");
        assertThat(ties.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-02-01T00:00:00Z"), 0, 3));

        // (3) Size 3, the last page: the other two tied rows, then lo-live in the lower LIVE
        // segment [from, Jan 1 00:00).
        RecordingLevel0Query last = new RecordingLevel0Query(webQuery);
        ProjectionPage lastPage = reader.readLevel0(last, new Level0Request(ALL, from, to, 3, 3));
        assertThat(lastPage.transactions()).isEqualTo(oracle.page(ViewKind.WEB, ALL, from, to, 6, 3))
                .extracting(TransactionEntry::sourceId).endsWith("lo-live").doesNotContain("before-from");
        assertThat(lastPage.totalItems()).isEqualTo(9);
        assertThat(last.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                from, Instant.parse("2034-02-01T00:00:00Z"), 3, 3));
    }

    /**
     * Spec 7.5 / P-3 within a request: totals and transactions come from ONE snapshot. A writer commit
     * (through the real writer, so counts move too) lands mid-read -- on the reader's first
     * Level0Query call, the live count of the partial upper month (it holds "m0", so it is counted),
     * after the month counts were read -- inside the range. Neither the total nor the page may see
     * it; the next read sees both. Two placements: in the live remainder (a leak shows in the live
     * count) and in a count-covered month (counts were already read, so a leak shows only in the
     * transactions).
     */
    @Test
    void a_read_sees_one_snapshot_despite_a_concurrent_commit() {
        assertOneSnapshot("2034-03-01T06:00:00Z", List.of("late", "m0", "f3", "f2", "f1", "j2", "j1"));
        reset();
        assertOneSnapshot("2034-02-20T10:00:00Z", List.of("m0", "f3", "late", "f2", "f1", "j2", "j1"));
    }

    private void assertOneSnapshot(String lateAt, List<String> afterOrder) {
        applyBatches(fiveTransactions());
        applyBatches(List.of(transaction("m0", "2034-03-01T01:00:00Z")));
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T12:00:00Z");
        Level0Request req = new Level0Request(ALL, from, to, 1, 10);
        ProjectionPage before = reader.readLevel0(webQuery, req);
        assertThat(before.totalItems()).isEqualTo(6);

        CommitOnFirstCall probe = new CommitOnFirstCall(webQuery, () -> writer.apply(new wqs.projection.ProjectionBatch(
                "wallet-transactions", List.of(transaction("late", lateAt)), 100L)));
        ProjectionPage during = reader.readLevel0(probe, req);

        assertThat(probe.fired).as("the commit happened inside the read").isTrue();
        assertThat(probe.firstCall).as("on the live count, after the month counts").isEqualTo("count");
        assertThat(during.totalItems()).as("total, late transaction at %s", lateAt).isEqualTo(6);
        assertThat(during.transactions()).as("transactions, late transaction at %s", lateAt).isEqualTo(before.transactions());
        ProjectionPage after = reader.readLevel0(webQuery, req);
        assertThat(after.totalItems()).isEqualTo(7);
        assertThat(after.transactions()).extracting(TransactionEntry::sourceId).containsExactlyElementsOf(afterOrder);
    }

    /** Delegate that runs {@code commit} (a writer-side transaction) before its first call of any kind. */
    static final class CommitOnFirstCall implements Level0Query {
        private final Level0Query delegate;
        private final Runnable commit;
        boolean fired;
        String firstCall;

        CommitOnFirstCall(Level0Query delegate, Runnable commit) {
            this.delegate = delegate;
            this.commit = commit;
        }

        private void once(String call) {
            if (!fired) {
                fired = true;
                firstCall = call;
                commit.run();
            }
        }

        @Override
        public ViewKind view() {
            return delegate.view();
        }

        @Override
        public ReadPolicy policy() {
            return delegate.policy();
        }

        @Override
        public long count(JdbcClient tx, Level0Filter f, Instant lo, Instant hi) {
            once("count");
            return delegate.count(tx, f, lo, hi);
        }

        @Override
        public List<TransactionKey> keys(JdbcClient tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
            once("keys");
            return delegate.keys(tx, f, lo, hi, offset, limit);
        }

        @Override
        public List<TransactionEntry> fetch(JdbcClient tx, long userId, List<TransactionKey> keys) {
            once("fetch");
            return delegate.fetch(tx, userId, keys);
        }
    }

    /**
     * Plan D2: a transactionTypes list of codes no row carries matches nothing -- an empty page
     * with totals 0, the same result the removed registry short-circuit gave, now from the
     * counters themselves (no lookup).
     */
    @Test
    void unknown_type_only_filter_matches_nothing() {
        applyBatches(fiveTransactions());
        RowCountingCounterReader counting = new RowCountingCounterReader();
        RecordingLevel0Query recording = new RecordingLevel0Query(webQuery);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");

        ProjectionPage page = probeReader(counting).readLevel0(recording, new Level0Request(
                ALL, java.util.Set.of("TT_NEVER_REGISTERED", "TT_ALSO_NOT"), from, to, 1, 10));

        assertThat(page.transactions()).isEmpty();
        assertThat(page.totalItems()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(recording.fetchCalls).as("no rows to fetch").isZero();
        // Control: a list with one known code is an ordinary read.
        RecordingLevel0Query ok = new RecordingLevel0Query(webQuery);
        ProjectionPage some = probeReader(counting).readLevel0(ok, new Level0Request(
                ALL, java.util.Set.of("TT_BUY", "TT_NEVER_REGISTERED"), from, to, 1, 10));
        assertThat(some.totalItems()).isEqualTo(5);
        assertThat(ok.keysCalls).hasSize(1);
    }

    @Test
    void both_filter_and_request_type_codes_are_refused() {
        Level0Filter codes = new Level0Filter(1L, java.util.Set.of(), java.util.Set.of(TxTypes.PLAY), false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Level0Request(
                        codes, java.util.Set.of("TT_BUY"), Instant.EPOCH, Instant.EPOCH, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Plan D1/D2: the type filter and the response's transactionType are the stored code itself --
     * no lookup on either side, so nothing outside the reader's single transaction is touched (M2).
     */
    @Test
    void type_filter_and_response_use_the_stored_code() {
        applyBatches(fiveTransactions());
        WebLevel0Query web = new WebLevel0Query(webMapper);
        ProjectionReader probe = new ProjectionReader(readerJdbc, readerTx, new CounterReader(),
                new ReadProperties(null), new ReadMetrics(new SimpleMeterRegistry()), highLevelQueries());

        ProjectionPage page = probe.readLevel0(web, new Level0Request(ALL, java.util.Set.of("TT_BUY"),
                Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-03-01T00:00:00Z"), 1, 10));

        assertThat(page.totalItems()).isEqualTo(5);
        assertThat(page.transactions()).hasSize(5).allSatisfy(e -> assertThat(e.transactionType()).isEqualTo("TT_BUY"));
    }

    // ------------------------------------------------------------------ readHighLevel (levels 1-2)

    private static final HighLevelFilter HL_ALL = new HighLevelFilter(1L, false);

    /**
     * Five page-index rows: Feb holds f1, f2, f3; Jan holds session "jb" (transactions j1 and j1b, one
     * row) and j2. Seven transactions, five rows -- a TRANSACTION-unit walk would land elsewhere.
     */
    private static List<ProjectionTransaction> fiveHighLevelRows() {
        return List.of(
                sessioned("j1", "2034-01-05T10:00:00Z", "jb"),
                sessioned("j1b", "2034-01-06T10:00:00Z", "jb"),
                transaction("j2", "2034-01-25T10:00:00Z"),
                transaction("f1", "2034-02-02T10:00:00Z"),
                sessioned("f2", "2034-02-14T10:00:00Z", "fb"),
                sessioned("f2b", "2034-02-14T11:00:00Z", "fb"),
                transaction("f3", "2034-02-27T10:00:00Z"));
    }

    private static HighLevelRequest hlRequest(ViewKind v, Instant from, Instant to, long page, long size) {
        return new HighLevelRequest(v, wqs.domain.AggregationLevel.L1, HL_ALL, from, to, page, size);
    }

    /**
     * Spec 7.6 at HIGH_LEVEL: page 3 of size 2 is offset 4; the newer months hold four entries --
     * March one, February two (a session and a row), January one -- so the walk lands in the December
     * month (session "db") with residual 0 and asks for one key -- naive OFFSET would query [from, to)
     * with offset 4. Metrics carry the request's aggregation level.
     */
    @Test
    void high_level_walk_lands_in_the_target_segment_with_a_small_residual() {
        applyBatches(List.of(
                sessioned("d1", "2033-12-05T10:00:00Z", "db"),
                sessioned("d1b", "2033-12-06T10:00:00Z", "db"),
                transaction("j2", "2034-01-25T10:00:00Z"),
                transaction("f1", "2034-02-02T10:00:00Z"),
                sessioned("f2", "2034-02-14T10:00:00Z", "fb"),
                sessioned("f2b", "2034-02-14T11:00:00Z", "fb"),
                transaction("m3", "2034-03-27T10:00:00Z")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectionReader probe = new ProjectionReader(
                readerJdbc, readerTx, new CounterReader(), new ReadProperties(null), new ReadMetrics(registry), highLevelQueries());
        RecordingHighLevelQuery recording = new RecordingHighLevelQuery(webHighLevelQuery);
        Instant from = MONTHLY_FROM;
        Instant to = MONTHLY_TO;

        HighLevelPage<HighLevelKey> page = probe.readHighLevel(recording,
                new HighLevelRequest(ViewKind.WEB, wqs.domain.AggregationLevel.L2, HL_ALL, from, to, 3, 2),
                (tx, keys) -> keys);

        assertThat(recording.keysCalls).containsExactly(new RecordingLevel0Query.KeysCall(
                Instant.parse("2033-12-01T00:00:00Z"), Instant.parse("2034-01-01T00:00:00Z"), 0, 1));
        assertThat(recording.countCalls).as("whole months need no live count").isZero();
        assertThat(page.totalRowCount()).isEqualTo(5);
        assertThat(page.entries()).extracting(HighLevelKey::refId).containsExactly("db");
        assertThat(registry.get("wqs.read.residual.offset").summary().max()).isZero();
        assertThat(registry.get("wqs.read.counter.rows").summary().max()).isEqualTo(4);
        assertThat(registry.get("wqs.read.partitions.touched").tag("aggregationLevel", "2").summary().count())
                .isEqualTo(1);
        assertThat(registry.get("wqs.read.partitions.touched").tag("aggregationLevel", "0").summary().count())
                .isZero();
        assertThat(registry.get("wqs.read.totalitems").tag("endpoint", "web").summary().max()).isEqualTo(5);
    }

    /** Ruling R4's public signature picks the view's query from the request. */
    @Test
    void read_high_level_selects_the_requested_views_index() {
        applyBatches(fiveHighLevelRows());
        applyBatches(List.of(new ProjectionTransaction(1L, SourceKind.PAYMENT, "pay", Instant.parse("2034-02-20T10:00:00Z"),
                null, null, null, null, TxTypes.NEUTRAL, new BigDecimal("3.0000"), "EUR", null, null, null)));
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");
        for (ViewKind v : ViewKind.values()) {
            HighLevelPage<HighLevelKey> page = reader.readHighLevel(hlRequest(v, from, to, 1, 10), (tx, keys) -> keys);
            assertThat(page.totalRowCount()).as(v.name()).isEqualTo(v == ViewKind.CC ? 6 : 5)
                    .isEqualTo(oracle.highLevelCount(v, HL_ALL, from, to));
            assertThat(page.entries()).as(v.name()).isEqualTo(oracle.highLevelPage(v, HL_ALL, from, to, 0, 10));
        }
    }

    @Test
    void reader_needs_exactly_one_high_level_query_per_view() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProjectionReader(readerJdbc, readerTx,
                        new CounterReader(), new ReadProperties(null), new ReadMetrics(new SimpleMeterRegistry()),
                        List.of(webHighLevelQuery)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CC");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProjectionReader(readerJdbc, readerTx,
                        new CounterReader(), new ReadProperties(null), new ReadMetrics(new SimpleMeterRegistry()),
                        List.of(webHighLevelQuery, ccHighLevelQuery, webHighLevelQuery)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WEB");
    }

    /**
     * Ruling R4 / phase-4 snapshot rule: the total, the key walk and the assembly run in ONE
     * reader transaction. A writer commit lands inside the read -- on the first key query, after
     * the counters were read, and again inside the assembler -- with a row newer than every other
     * in the range. The page and the assembler's own reads through {@code tx} see neither.
     */
    @Test
    void total_walk_and_assembly_share_one_snapshot() {
        applyBatches(fiveHighLevelRows());
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T12:00:00Z");
        Runnable[] commits = {
            () -> applyBatches(List.of(transaction("late1", "2034-03-01T06:00:00Z"))),
            () -> applyBatches(List.of(transaction("late2", "2034-03-01T07:00:00Z"))),
        };
        HighLevelQuery commitOnKeys = new HighLevelQuery() {
            boolean fired;

            @Override
            public ViewKind view() {
                return ViewKind.WEB;
            }

            @Override
            public ReadPolicy policy() {
                return ReadPolicy.WEB;
            }

            @Override
            public long count(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi) {
                return webHighLevelQuery.count(tx, f, lo, hi);
            }

            @Override
            public List<HighLevelKey> keys(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi, long o, int l) {
                if (!fired) {
                    fired = true;
                    commits[0].run();
                }
                return webHighLevelQuery.keys(tx, f, lo, hi, o, l);
            }
        };
        long[] seenByAssembler = {-1};

        HighLevelPage<HighLevelKey> during = reader.readHighLevel(commitOnKeys,
                hlRequest(ViewKind.WEB, from, to, 1, 10), (tx, keys) -> {
                    commits[1].run();
                    seenByAssembler[0] = webHighLevelQuery.count(tx, HL_ALL, from, to);
                    return keys;
                });

        assertThat(during.totalRowCount()).isEqualTo(5);
        assertThat(during.entries()).extracting(HighLevelKey::refId).containsExactly("f3", "fb", "f1", "j2", "jb");
        assertThat(seenByAssembler[0]).as("assembler reads the same snapshot").isEqualTo(5);
        HighLevelPage<HighLevelKey> after = reader.readHighLevel(hlRequest(ViewKind.WEB, from, to, 1, 10), (tx, k) -> k);
        assertThat(after.totalRowCount()).isEqualTo(7);
        assertThat(after.entries()).extracting(HighLevelKey::refId).startsWith("late2", "late1");
    }

    /** Spec 7.9 at HIGH_LEVEL: past the end (overflow included) is empty with the right total, and nothing is assembled. */
    @Test
    void high_level_beyond_last_page_is_empty_and_never_assembles() {
        applyBatches(fiveHighLevelRows());
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");
        long[][] beyond = {{4, 2}, {Long.MAX_VALUE / 2, Integer.MAX_VALUE}, {3, Long.MAX_VALUE}, {Long.MAX_VALUE, Long.MAX_VALUE}};
        for (long[] pp : beyond) {
            HighLevelPage<HighLevelKey> page = reader.readHighLevel(hlRequest(ViewKind.WEB, from, to, pp[0], pp[1]),
                    (tx, keys) -> {
                        throw new AssertionError("assembled an empty page " + pp[0] + "/" + pp[1]);
                    });
            assertThat(page.entries()).isEmpty();
            assertThat(page.totalRowCount()).isEqualTo(5);
        }
        assertThat(reader.readHighLevel(hlRequest(ViewKind.WEB, from, to, 1, Long.MAX_VALUE), (tx, k) -> k).entries())
                .hasSize(5);
        assertThat(reader.effectivePageSize(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    static ProjectionTransaction sessioned(String id, String at, String batchId) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, Instant.parse(at), "P11", "keno", batchId,
                batchId + "-p0", TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }

    /** 3 transactions in Feb 2034, 2 in Jan 2034, all user 1. */
    private static List<ProjectionTransaction> fiveTransactions() {
        return List.of(
                transaction("j1", "2034-01-05T10:00:00Z"),
                transaction("j2", "2034-01-25T10:00:00Z"),
                transaction("f1", "2034-02-02T10:00:00Z"),
                transaction("f2", "2034-02-14T10:00:00Z"),
                transaction("f3", "2034-02-27T10:00:00Z"));
    }

    private static final Instant MONTHLY_FROM = Instant.parse("2033-12-01T00:00:00Z");
    private static final Instant MONTHLY_TO = Instant.parse("2034-04-01T00:00:00Z");

    /** One transaction in each of Mar, Feb and Jan 2034, two in Dec 2033, all user 1: whole months of [MONTHLY_FROM, MONTHLY_TO). */
    private static List<ProjectionTransaction> fiveMonthlyTransactions() {
        return List.of(
                transaction("d1", "2033-12-05T10:00:00Z"),
                transaction("d2", "2033-12-20T10:00:00Z"),
                transaction("j1", "2034-01-15T10:00:00Z"),
                transaction("f1", "2034-02-14T10:00:00Z"),
                transaction("m1", "2034-03-10T10:00:00Z"));
    }

    /** Builds a reader around a test counter reader, with no ceiling and a private registry. */
    private ProjectionReader probeReader(CounterReader counters) {
        return new ProjectionReader(
                readerJdbc, readerTx, counters, new ReadProperties(null), new ReadMetrics(new SimpleMeterRegistry()), highLevelQueries());
    }

    /** Records every month row {@link CounterReader#monthCounts} returns, without a mocking framework. */
    static final class RowCountingCounterReader extends CounterReader {
        final List<YearMonth> monthKeys = new ArrayList<>();
        int monthCalls;

        @Override
        public Map<YearMonth, Long> monthCounts(
                JdbcClient tx, ReadPolicy policy, long userId, wqs.domain.PageUnit unit,
                java.util.Set<String> productTypes, java.util.Set<String> typeCodes,
                boolean excludeCancelled, YearMonth first, YearMonth last) {
            Map<YearMonth, Long> rows = super.monthCounts(tx, policy, userId, unit, productTypes, typeCodes,
                    excludeCancelled, first, last);
            monthKeys.addAll(rows.keySet());
            monthCalls++;
            return rows;
        }
    }

    static ProjectionTransaction transaction(String id, String at) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, Instant.parse(at), "P11", "keno", null, null,
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
