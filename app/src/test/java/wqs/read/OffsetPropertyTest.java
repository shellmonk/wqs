package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
import wqs.gen.ReadGenerators;
import wqs.gen.ReadGenerators.ReadQuery;

/**
 * Property P-6 (spec 7.6, 8.1): the counter-assisted offset page -- segment walk, residual, one
 * ordered key query from the target segment's end -- equals the naive OFFSET/LIMIT page.
 */
@JqwikSpringSupport
@SpringBootTest
class OffsetPropertyTest extends ReadPropertyBase {

    /** A query plus its paging: pageSize in [1, 20]; pagePick resolved against the oracle count. */
    record PagedQuery(ReadQuery query, int pageSize, int pagePick) {}

    @Property(tries = 30)
    void fast_page_equals_naive_offset(
            @ForAll("history") List<ProjectionItem> items,
            @ForAll("pagedQueries") List<PagedQuery> queries) {
        reset();
        applyBatches(items);
        for (PagedQuery pq : queries) {
            ReadQuery q = pq.query();
            long count = oracle.count(q.view(), q.filter(), q.from(), q.to());
            // page in [1, ceil(count / pageSize) + 2]: inside, on the last page and beyond it.
            long page = 1 + pq.pagePick() % (Math.ceilDiv(count, pq.pageSize()) + 2);
            RecordingLevel0Query recording = new RecordingLevel0Query(queryFor(q.view()));
            ProjectionPage got = reader.readLevel0(recording,
                    new Level0Request(q.filter(), q.from(), q.to(), page, pq.pageSize()));
            String as = pq + " page=" + page;
            assertThat(got.totalItems()).as(as).isEqualTo(count);
            assertThat(got.items()).as(as).isEqualTo(oracle.page(
                    q.view(), q.filter(), q.from(), q.to(), (page - 1) * pq.pageSize(), pq.pageSize()));
            assertWalkLandsInsideOneSegment(q, got, recording, as);
        }
    }

    /**
     * The optimisation itself (spec 7.6), not just its result: a non-empty page runs ONE key
     * query from {@code from} to the end of a decomposition segment, and its offset is a residual
     * strictly inside that segment -- checked against an independent oracle count of the segment.
     * Naive OFFSET (key query over [from, to) with the full offset) fails this.
     */
    private void assertWalkLandsInsideOneSegment(
            ReadQuery q, ProjectionPage got, RecordingLevel0Query recording, String as) {
        if (got.items().isEmpty()) {
            assertThat(recording.keysCalls).as(as + " empty page runs no key query").isEmpty();
            return;
        }
        assertThat(recording.keysCalls).as(as).hasSize(1);
        RecordingLevel0Query.KeysCall call = recording.keysCalls.get(0);
        assertThat(call.lo()).as(as).isEqualTo(q.from());
        Segment target = RangeDecomposition.newestFirst(q.from(), q.to()).stream()
                .filter(s -> s.end().equals(call.hi()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(as + ": key query hi " + call.hi() + " is no segment end"));
        long segmentCount = oracle.count(q.view(), q.filter(), target.start(), call.hi());
        assertThat(call.offset()).as(as + " residual inside " + target).isGreaterThanOrEqualTo(0).isLessThan(segmentCount);
    }

    @Provide
    Arbitrary<List<ProjectionItem>> history() {
        return ReadGenerators.history();
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
