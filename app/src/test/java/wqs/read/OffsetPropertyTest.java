package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.gen.ReadGenerators;
import wqs.gen.ReadGenerators.ReadQuery;

/**
 * Property P-6 (spec 7.6, 8.1): the counter-assisted offset page -- segment walk, residual, one
 * ordered key query from the target segment's end -- equals the naive OFFSET/LIMIT page.
 */
@Tag("property")
@JqwikSpringSupport
@SpringBootTest
class OffsetPropertyTest extends ReadPropertyBase {

    /** A query plus its paging: pageSize in [1, 20]; pagePick resolved against the oracle count. */
    record PagedQuery(ReadQuery query, int pageSize, int pagePick) {}

    @Property(tries = 30)
    void fast_page_equals_naive_offset(
            @ForAll("history") List<ProjectionTransaction> transactions,
            @ForAll("pagedQueries") List<PagedQuery> queries) {
        reset();
        applyBatches(transactions);
        for (PagedQuery pq : queries) {
            ReadQuery q = pq.query();
            long count = oracle.count(q.view(), q.filter(), q.transactionTypes(), q.from(), q.to());
            // page in [1, ceil(count / pageSize) + 2]: inside, on the last page and beyond it.
            long page = 1 + pq.pagePick() % (Math.ceilDiv(count, pq.pageSize()) + 2);
            RecordingLevel0Query recording = new RecordingLevel0Query(queryFor(q.view()));
            ProjectionPage got = reader.readLevel0(recording,
                    q.request(page, pq.pageSize()));
            String as = pq + " page=" + page;
            assertThat(got.totalItems()).as(as).isEqualTo(count);
            assertThat(got.transactions()).as(as).isEqualTo(oracle.page(
                    q.view(), q.filter(), q.transactionTypes(), q.from(), q.to(), (page - 1) * pq.pageSize(), pq.pageSize()));
            assertWalkLandsInsideOneSegment(q, got.transactions().isEmpty(), recording.keysCalls,
                    (lo, hi) -> oracle.count(q.view(), q.filter(), q.transactionTypes(), lo, hi), as);
        }
    }

    /**
     * P-6 at the HIGH_LEVEL page unit (levels 1-2): the walk over the page index's counters and
     * live counts lands on the naive OFFSET page of the high-level index, in its total order
     * ("occurredAt" desc, "kind" desc, "refId" desc). The assembler is the identity on the keys,
     * so entries are exactly the keys the walk produced.
     */
    @Property(tries = 30)
    void fast_high_level_page_equals_naive_offset(
            @ForAll("highLevelHistory") List<ProjectionTransaction> transactions,
            @ForAll("pagedQueries") List<PagedQuery> queries) {
        reset();
        applyBatches(transactions);
        for (PagedQuery pq : queries) {
            ReadQuery q = pq.query();
            HighLevelFilter f = q.highLevelFilter();
            long count = oracle.highLevelCount(q.view(), f, q.from(), q.to());
            long page = 1 + pq.pagePick() % (Math.ceilDiv(count, pq.pageSize()) + 2);
            AggregationLevel level = pq.pagePick() % 2 == 0 ? AggregationLevel.L1 : AggregationLevel.L2;
            RecordingHighLevelQuery recording = new RecordingHighLevelQuery(highLevelQueryFor(q.view()));
            HighLevelPage<HighLevelKey> got = reader.readHighLevel(
                    recording, q.highLevelRequest(level, page, pq.pageSize()), (tx, keys) -> keys);
            String as = pq + " page=" + page;
            assertThat(got.totalRowCount()).as(as).isEqualTo(count);
            assertThat(got.entries()).as(as).isEqualTo(oracle.highLevelPage(
                    q.view(), f, q.from(), q.to(), (page - 1) * pq.pageSize(), pq.pageSize()));
            assertWalkLandsInsideOneSegment(q, got.entries().isEmpty(), recording.keysCalls,
                    (lo, hi) -> oracle.highLevelCount(q.view(), f, lo, hi), as);
        }
    }

    /**
     * The optimisation itself (spec 7.6, plan D4), not just its result: a non-empty page runs ONE
     * key query from the start of the segment holding its last unit to the end of a decomposition segment, and its offset is a residual
     * strictly inside that segment -- checked against an independent oracle count of the segment.
     * Naive OFFSET (key query over [from, to) with the full offset) fails this.
     */
    private static void assertWalkLandsInsideOneSegment(
            ReadQuery q, boolean emptyPage, List<RecordingLevel0Query.KeysCall> keysCalls,
            BiFunction<Instant, Instant, Long> oracleCount, String as) {
        if (emptyPage) {
            assertThat(keysCalls).as(as + " empty page runs no key query").isEmpty();
            return;
        }
        assertThat(keysCalls).as(as).hasSize(1);
        RecordingLevel0Query.KeysCall call = keysCalls.get(0);
        // Plan D4: the lower bound is the start of the segment holding the page's last unit --
        // the oldest segment the page needs, never below from.
        Segment bottom = RangeDecomposition.newestFirst(q.from(), q.to()).stream()
                .filter(s -> s.start().equals(call.lo()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(as + ": key query lo " + call.lo() + " is no segment start"));
        assertThat(call.lo()).as(as).isAfterOrEqualTo(q.from());
        long need = call.offset() + call.limit();
        assertThat(oracleCount.apply(call.lo(), call.hi())).as(as + " page inside [lo, hi)").isGreaterThanOrEqualTo(need);
        if (bottom.end().isBefore(call.hi())) {
            assertThat(oracleCount.apply(bottom.end(), call.hi())).as(as + " lo is the oldest segment needed")
                    .isLessThan(need);
        }
        Segment target = RangeDecomposition.newestFirst(q.from(), q.to()).stream()
                .filter(s -> s.end().equals(call.hi()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(as + ": key query hi " + call.hi() + " is no segment end"));
        long segmentCount = oracleCount.apply(target.start(), call.hi());
        assertThat(call.offset()).as(as + " residual inside " + target).isGreaterThanOrEqualTo(0).isLessThan(segmentCount);
    }

    @Provide
    Arbitrary<List<ProjectionTransaction>> history() {
        return ReadGenerators.history();
    }

    @Provide
    Arbitrary<List<ProjectionTransaction>> highLevelHistory() {
        return ReadGenerators.highLevelHistory();
    }

    @Provide
    Arbitrary<List<PagedQuery>> pagedQueries() {
        return Combinators.combine(
                        ReadGenerators.queries(),
                        Arbitraries.integers().between(1, 20),
                        Arbitraries.integers().between(0, 10_000))
                .as(PagedQuery::new)
                .list().ofSize(10);
    }
}
