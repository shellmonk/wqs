package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
import wqs.gen.ReadGenerators;
import wqs.gen.ReadGenerators.ReadQuery;

/**
 * Property P-3 (spec 7.1, 8.1): pages 1..totalPages concatenate to the full ordered result --
 * no gap, no duplicate, same order -- and the page after the last is empty with the same totals.
 */
@JqwikSpringSupport
@SpringBootTest
class PaginationPropertyTest extends ReadPropertyBase {

    record SizedQuery(ReadQuery query, int pageSize) {}

    // FULL shrinking runs on the test thread. BOUNDED (the default) shrinks on an async thread
    // and abandons it at the timeout while its in-flight try keeps writing to the shared
    // database, racing whatever runs next (deadlocks, strict-updater failures, a wrong report).
    @Property(tries = 30, shrinking = ShrinkingMode.FULL)
    void all_pages_concatenate_to_the_full_result(
            @ForAll("history") List<ProjectionItem> items,
            @ForAll("sizedQueries") List<SizedQuery> queries) {
        reset();
        applyBatches(items);
        for (SizedQuery sq : queries) {
            ReadQuery q = sq.query();
            Level0Query lq = queryFor(q.view());
            List<ItemEntry> full = oracle.page(q.view(), q.filter(), q.from(), q.to(), 0, Integer.MAX_VALUE);

            ProjectionPage first = read(lq, q, 1, sq.pageSize());
            long totalPages = first.totalPages();
            assertThat(first.totalItems()).as(sq.toString()).isEqualTo(full.size());
            assertThat(totalPages).as(sq.toString()).isEqualTo(Math.ceilDiv(full.size(), sq.pageSize()));

            List<ItemEntry> all = new ArrayList<>();
            for (long p = 1; p <= totalPages; p++) {
                ProjectionPage page = p == 1 ? first : read(lq, q, p, sq.pageSize());
                assertThat(page.totalItems()).as(sq + " page=" + p).isEqualTo(full.size());
                assertThat(page.totalPages()).as(sq + " page=" + p).isEqualTo(totalPages);
                assertThat(page.items()).as(sq + " page=" + p).isNotEmpty().hasSizeLessThanOrEqualTo(sq.pageSize());
                all.addAll(page.items());
            }
            assertThat(all).as(sq.toString()).isEqualTo(full);

            ProjectionPage beyond = read(lq, q, totalPages + 1, sq.pageSize());
            assertThat(beyond.items()).as(sq + " beyond").isEmpty();
            assertThat(beyond.totalItems()).as(sq + " beyond").isEqualTo(full.size());
            assertThat(beyond.totalPages()).as(sq + " beyond").isEqualTo(totalPages);
        }
    }

    private ProjectionPage read(Level0Query lq, ReadQuery q, long page, int pageSize) {
        return reader.readLevel0(lq, new Level0Request(q.filter(), q.from(), q.to(), page, pageSize));
    }

    @Provide
    Arbitrary<List<ProjectionItem>> history() {
        return ReadGenerators.history();
    }

    @Provide
    Arbitrary<List<SizedQuery>> sizedQueries() {
        return Combinators.combine(ReadGenerators.queries(), Arbitraries.integers().between(1, 20))
                .as(SizedQuery::new)
                .list().ofSize(10);
    }
}
