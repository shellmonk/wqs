package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;
import wqs.read.Segment.Tier;

/**
 * Levels 1-2 paging over the high-level page index (spec 5.6, 7.3-7.6), on one fixed dataset,
 * against the naive-OFFSET and live-COUNT oracles: P-3 (pages concatenate to the full ordered
 * set), P-5 (counter totals equal a live COUNT) and P-6 (each page equals naive OFFSET, and the
 * walk -- not a naive OFFSET -- produced it), for page sizes 1-3, both views, ranges with whole
 * months, partial days and bounds exactly on a tie instant, and the filters none / one product
 * type plus an absent one / excludeCancelled.
 *
 * <p>The dataset is tie-heavy: three instants (two on month boundaries) each hold two or three
 * sessions (kind 0), unsessioned transactions (kind 1), a claimable prize (kind 3) and a payment
 * (kind 4, /cc only), so the "kind" and "refId" tie-breaks decide where pages split. It spans
 * Dec 2033 - Mar 2034 and three product types (keno, lottery, none), and includes a fully
 * cancelled session, a cancelled unsessioned item and a session that re-anchors onto a tie
 * instant after a later item opened it.
 */
@SpringBootTest
class HighLevelPagingTest extends ReadPropertyBase {

    private static final long USER = 1L;
    private static final String[] TIES = {"2034-01-01T00:00:00Z", "2034-02-14T10:00:00Z", "2034-03-01T00:00:00Z"};

    private record Range(Instant from, Instant to) {
        @Override
        public String toString() {
            return "[" + from + ", " + to + ")";
        }
    }

    private static final List<Range> RANGES = List.of(
            // whole months only
            range("2033-12-01T00:00:00Z", "2034-04-01T00:00:00Z"),
            // partial days at both ends; the upper one keeps the Mar 1 tie group in a LIVE segment
            range("2033-12-20T06:00:00Z", "2034-03-01T00:00:01Z"),
            // upper bound exactly on the Feb 14 tie instant: that group is excluded
            range("2034-01-01T00:00:00Z", "2034-02-14T10:00:00Z"),
            // one second: the Feb 14 tie group alone, in one LIVE segment
            range("2034-02-14T10:00:00Z", "2034-02-14T10:00:01Z"));

    private static final List<HighLevelFilter> FILTERS = List.of(
            new HighLevelFilter(USER, false),
            new HighLevelFilter(USER, Set.of("keno", "absent-product"), false),
            new HighLevelFilter(USER, true),
            new HighLevelFilter(USER, Set.of("lottery"), true));

    @BeforeEach
    void seed() {
        reset();
        applyBatches(dataset());
    }

    /** Guards the dataset itself: without cross-kind ties the tie-break assertions below are hollow. */
    @Test
    void dataset_has_mixed_kind_ties_on_every_tie_instant() {
        Range all = RANGES.get(0);
        for (ViewKind v : ViewKind.values()) {
            List<HighLevelKey> full = oracle.highLevelPage(v, FILTERS.get(0), all.from(), all.to(), 0, Integer.MAX_VALUE);
            Map<Instant, List<HighLevelKey>> byInstant = full.stream()
                    .collect(Collectors.groupingBy(HighLevelKey::occurredAt));
            for (String tie : TIES) {
                List<HighLevelKey> group = byInstant.get(Instant.parse(tie));
                assertThat(group).as(v + " " + tie).hasSizeGreaterThanOrEqualTo(v == ViewKind.CC ? 7 : 6);
                assertThat(group).extracting(HighLevelKey::kind).as(v + " " + tie)
                        .contains((short) 0, (short) 1, (short) 3);
                assertThat(group.stream().filter(k -> k.kind() == 0)).as(v + " sessions at " + tie).hasSizeGreaterThan(1);
            }
            // the cancelled session and item exist, and excludeCancelled hides them
            long hidden = oracle.highLevelCount(v, FILTERS.get(0), all.from(), all.to())
                    - oracle.highLevelCount(v, FILTERS.get(2), all.from(), all.to());
            assertThat(hidden).as(v + " hidden rows").isEqualTo(2);
        }
    }

    /** P-3 + P-6: every page equals naive OFFSET, pages concatenate to the full set, and the walk ran. */
    @Test
    void every_page_equals_naive_offset_and_pages_concatenate_to_the_full_set() {
        int pagesChecked = 0;
        for (ViewKind v : ViewKind.values()) {
            for (Range r : RANGES) {
                for (HighLevelFilter f : FILTERS) {
                    List<HighLevelKey> full = oracle.highLevelPage(v, f, r.from(), r.to(), 0, Integer.MAX_VALUE);
                    for (int size = 1; size <= 3; size++) {
                        String as = v + " " + r + " " + f + " size=" + size;
                        long pages = Math.ceilDiv(full.size(), size);
                        List<HighLevelKey> all = new ArrayList<>();
                        for (long p = 1; p <= pages + 1; p++) {
                            RecordingHighLevelQuery recording = new RecordingHighLevelQuery(highLevelQueryFor(v));
                            HighLevelPage<HighLevelKey> page = reader.readHighLevel(recording,
                                    new HighLevelRequest(v, p % 2 == 0 ? AggregationLevel.L2 : AggregationLevel.L1,
                                            f, r.from(), r.to(), p, size),
                                    (tx, keys) -> keys);
                            String at = as + " page=" + p;
                            assertThat(page.totalRowCount()).as(at).isEqualTo(full.size());
                            assertThat(page.entries()).as(at)
                                    .isEqualTo(oracle.highLevelPage(v, f, r.from(), r.to(), (p - 1) * size, size));
                            assertWalkRan(v, f, r, page.entries().isEmpty(), recording, at);
                            all.addAll(page.entries());
                            pagesChecked++;
                        }
                        assertThat(all).as(as).doesNotHaveDuplicates().isEqualTo(full);
                    }
                }
            }
        }
        assertThat(pagesChecked).isGreaterThan(500);
    }

    /** P-5: the counter-assisted total equals a live COUNT over the page index, for every range and filter. */
    @Test
    void totals_equal_a_live_count() {
        for (ViewKind v : ViewKind.values()) {
            for (Range r : RANGES) {
                for (HighLevelFilter f : FILTERS) {
                    long expected = oracle.highLevelCount(v, f, r.from(), r.to());
                    String as = v + " " + r + " " + f;
                    assertThat(reader.total(highLevelQueryFor(v), f, r.from(), r.to())).as(as).isEqualTo(expected);
                    assertThat(reader.readHighLevel(highLevelQueryFor(v),
                                    new HighLevelRequest(v, AggregationLevel.L1, f, r.from(), r.to(), 1, 1),
                                    (tx, keys) -> keys).totalRowCount())
                            .as(as).isEqualTo(expected);
                }
            }
        }
        // An absent product type alone: an empty, correct page.
        HighLevelFilter absent = new HighLevelFilter(USER, Set.of("absent-product"), false);
        Range all = RANGES.get(0);
        HighLevelPage<HighLevelKey> none = reader.readHighLevel(new HighLevelRequest(
                ViewKind.WEB, AggregationLevel.L1, absent, all.from(), all.to(), 1, 3), (tx, keys) -> keys);
        assertThat(none.totalRowCount()).isZero();
        assertThat(none.entries()).isEmpty();
    }

    /** Whole months at HIGH_LEVEL come from HIGH_LEVEL counters alone, which count a session once. */
    @Test
    void whole_months_are_counted_from_high_level_counters_only() {
        Range all = RANGES.get(0);
        for (ViewKind v : ViewKind.values()) {
            RecordingHighLevelQuery recording = new RecordingHighLevelQuery(highLevelQueryFor(v));
            SegmentCounts sc = readerDsl.transactionResult(cfg ->
                    reader.segmentCounts(DSL.using(cfg), recording, FILTERS.get(0), all.from(), all.to()));
            assertThat(sc.segments()).extracting(Segment::tier).containsOnly(Tier.MONTH);
            assertThat(recording.countCalls).as(v + " live counts").isZero();
            assertThat(sc.counterRows()).as(v.name()).isEqualTo(4);
            assertThat(sc.total()).as(v.name()).isEqualTo(oracle.highLevelCount(v, FILTERS.get(0), all.from(), all.to()));
            // Fewer page rows than items: sessions are one row each, cancels none.
            assertThat(sc.total()).as(v.name()).isLessThan(oracle.count(v, new Level0Filter(USER, false), all.from(), all.to()));
        }
    }

    /**
     * The optimisation itself (spec 7.6), as in phase 4's P-6: a non-empty page runs ONE key
     * query from {@code from} to the end of a decomposition segment, with an offset that is a
     * residual strictly inside that segment. Naive OFFSET -- [from, to) with the full offset --
     * fails this whenever the page starts below the newest segment.
     */
    private void assertWalkRan(ViewKind v, HighLevelFilter f, Range r, boolean emptyPage,
                               RecordingHighLevelQuery recording, String as) {
        if (emptyPage) {
            assertThat(recording.keysCalls).as(as + " empty page runs no key query").isEmpty();
            return;
        }
        assertThat(recording.keysCalls).as(as).hasSize(1);
        RecordingLevel0Query.KeysCall call = recording.keysCalls.get(0);
        assertThat(call.lo()).as(as).isEqualTo(r.from());
        Segment target = RangeDecomposition.newestFirst(r.from(), r.to()).stream()
                .filter(s -> s.end().equals(call.hi()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(as + ": key query hi " + call.hi() + " is no segment end"));
        long segmentCount = oracle.highLevelCount(v, f, target.start(), call.hi());
        assertThat(call.offset()).as(as + " residual inside " + target).isGreaterThanOrEqualTo(0).isLessThan(segmentCount);
    }

    // ------------------------------------------------------------------ the dataset

    private static List<ProjectionItem> dataset() {
        List<ProjectionItem> d = new ArrayList<>();
        Function<String, Instant> at = Instant::parse;
        for (int g = 0; g < TIES.length; g++) {
            Instant tie = at.apply(TIES[g]);
            String p = "g" + g;
            // Session A opens at the tie (keno, its first item) and continues an hour later.
            d.add(item(SourceKind.TRANSACTION, p + "-a1", tie, "keno", p + "-sa"));
            d.add(item(SourceKind.TRANSACTION, p + "-a2", tie.plus(Duration.ofHours(1)), "lottery", p + "-sa"));
            // Session B opens at the tie (lottery) and continues two days later.
            d.add(item(SourceKind.TRANSACTION, p + "-b1", tie, "lottery", p + "-sb"));
            d.add(item(SourceKind.CLAIMABLE_PRIZE, p + "-b2", tie.plus(Duration.ofDays(2)), "lottery", p + "-sb"));
            // Unsessioned peers on the same instant: transactions (kind 1), a prize (3), a payment (4, /cc).
            d.add(item(SourceKind.TRANSACTION, p + "-t1", tie, "keno", null));
            d.add(item(SourceKind.TRANSACTION, p + "-t2", tie, null, null));
            d.add(item(SourceKind.TRANSACTION, p + "-t3", tie, "lottery", null));
            d.add(item(SourceKind.CLAIMABLE_PRIZE, p + "-z1", tie, "keno", null));
            d.add(item(SourceKind.PAYMENT, p + "-y1", tie, null, null));
        }
        // Session C of the first group arrives late-first, then re-anchors onto the tie instant.
        d.add(item(SourceKind.TRANSACTION, "g0-c2", at.apply("2034-01-01T03:00:00Z"), null, "g0-sc"));
        d.add(item(SourceKind.TRANSACTION, "g0-c1", at.apply(TIES[0]), null, "g0-sc"));
        // Fully cancelled session: g1's session B (both items); a cancelled unsessioned item: g2-t1.
        d.add(cancel("g1-b1-x", at.apply("2034-02-14T12:00:00Z"), "g1-b1"));
        d.add(cancel("g1-b2-x", at.apply("2034-02-16T12:00:00Z"), "g1-b2"));
        d.add(cancel("g2-t1-x", at.apply("2034-03-01T05:00:00Z"), "g2-t1"));
        // Scattered rows: December (two tied on a partial-day bound), a session across Jan/Feb,
        // the last second of February, just after the Mar 1 tie, and a March payment.
        d.add(item(SourceKind.TRANSACTION, "d1", at.apply("2033-12-05T08:00:00Z"), "keno", null));
        d.add(item(SourceKind.TRANSACTION, "d2", at.apply("2033-12-20T06:00:00Z"), "lottery", null));
        d.add(item(SourceKind.CLAIMABLE_PRIZE, "d3", at.apply("2033-12-20T06:00:00Z"), "keno", null));
        d.add(item(SourceKind.TRANSACTION, "j1", at.apply("2034-01-15T09:00:00Z"), "keno", "jsess"));
        d.add(item(SourceKind.TRANSACTION, "j2", at.apply("2034-02-03T09:00:00Z"), "keno", "jsess"));
        d.add(item(SourceKind.TRANSACTION, "f1", at.apply("2034-02-28T23:59:59Z"), null, null));
        d.add(item(SourceKind.TRANSACTION, "m1", at.apply("2034-03-01T00:00:01Z"), "keno", null));
        d.add(item(SourceKind.PAYMENT, "m2", at.apply("2034-03-10T10:00:00Z"), null, null));
        return d;
    }

    private static ProjectionItem item(SourceKind source, String id, Instant at, String productType, String batchId) {
        return new ProjectionItem(USER, source, id, at, productType == null ? null : "P-" + productType, productType,
                batchId, batchId == null ? null : batchId + "-p0", TxTypes.PLAY, new BigDecimal("1.0000"), "EUR",
                null, null, null);
    }

    private static ProjectionItem cancel(String id, Instant at, String target) {
        String source = target.contains("-b2") ? "prize" : "tx";
        return new ProjectionItem(USER, source.equals("prize") ? SourceKind.CLAIMABLE_PRIZE : SourceKind.TRANSACTION,
                id, at, null, null, null, null, TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, target, null);
    }

    private static Range range(String from, String to) {
        return new Range(Instant.parse(from), Instant.parse(to));
    }
}
