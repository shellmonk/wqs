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
import net.jqwik.api.Tag;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.gen.ReadGenerators;
import wqs.gen.ReadGenerators.ReadQuery;

/**
 * Property P-3 (spec 7.1, 8.1): pages 1..totalPages concatenate to the full ordered result --
 * no gap, no duplicate, same order -- and the page after the last is empty with the same totals.
 */
@Tag("property")
@JqwikSpringSupport
@SpringBootTest
class PaginationPropertyTest extends ReadPropertyBase {

    record SizedQuery(ReadQuery query, int pageSize) {}

    @Property(tries = 30)
    void all_pages_concatenate_to_the_full_result(
            @ForAll("history") List<ProjectionTransaction> transactions,
            @ForAll("sizedQueries") List<SizedQuery> queries) {
        reset();
        applyBatches(transactions);
        for (SizedQuery sq : queries) {
            ReadQuery q = sq.query();
            Level0Query lq = queryFor(q.view());
            List<TransactionEntry> full = oracle.page(q.view(), q.filter(), q.transactionTypes(), q.from(), q.to(), 0, Integer.MAX_VALUE);

            ProjectionPage first = read(lq, q, 1, sq.pageSize());
            long totalPages = first.totalPages();
            assertThat(first.totalItems()).as(sq.toString()).isEqualTo(full.size());
            assertThat(totalPages).as(sq.toString()).isEqualTo(Math.ceilDiv(full.size(), sq.pageSize()));

            List<TransactionEntry> all = new ArrayList<>();
            for (long p = 1; p <= totalPages; p++) {
                ProjectionPage page = p == 1 ? first : read(lq, q, p, sq.pageSize());
                assertThat(page.totalItems()).as(sq + " page=" + p).isEqualTo(full.size());
                assertThat(page.totalPages()).as(sq + " page=" + p).isEqualTo(totalPages);
                assertThat(page.transactions()).as(sq + " page=" + p).isNotEmpty().hasSizeLessThanOrEqualTo(sq.pageSize());
                all.addAll(page.transactions());
            }
            assertThat(all).as(sq.toString()).isEqualTo(full);

            ProjectionPage beyond = read(lq, q, totalPages + 1, sq.pageSize());
            assertThat(beyond.transactions()).as(sq + " beyond").isEmpty();
            assertThat(beyond.totalItems()).as(sq + " beyond").isEqualTo(full.size());
            assertThat(beyond.totalPages()).as(sq + " beyond").isEqualTo(totalPages);
        }
    }

    /**
     * P-3 at the HIGH_LEVEL page unit: pages 1..ceil(total / pageSize) of the page index
     * concatenate to its full ordered key list, and the page after the last is empty with the
     * same total. Ties on "occurredAt" (sessions beside unsessioned transactions) cross page boundaries,
     * so a page-local or flipped tie-break duplicates or drops a row here.
     */
    @Property(tries = 30)
    void all_high_level_pages_concatenate_to_the_full_result(
            @ForAll("highLevelHistory") List<ProjectionTransaction> transactions,
            @ForAll("sizedQueries") List<SizedQuery> queries) {
        reset();
        applyBatches(transactions);
        for (SizedQuery sq : queries) {
            ReadQuery q = sq.query();
            HighLevelQuery hq = highLevelQueryFor(q.view());
            List<HighLevelKey> full = oracle.highLevelPage(q.view(), q.highLevelFilter(), q.from(), q.to(), 0, Integer.MAX_VALUE);
            long totalPages = Math.ceilDiv(full.size(), sq.pageSize());

            List<HighLevelKey> all = new ArrayList<>();
            for (long p = 1; p <= totalPages; p++) {
                HighLevelPage<HighLevelKey> page = readHighLevel(hq, q, p, sq.pageSize());
                assertThat(page.totalRowCount()).as(sq + " page=" + p).isEqualTo(full.size());
                assertThat(page.entries()).as(sq + " page=" + p).isNotEmpty().hasSizeLessThanOrEqualTo(sq.pageSize());
                all.addAll(page.entries());
            }
            assertThat(all).as(sq.toString()).doesNotHaveDuplicates().isEqualTo(full);

            HighLevelPage<HighLevelKey> beyond = readHighLevel(hq, q, totalPages + 1, sq.pageSize());
            assertThat(beyond.entries()).as(sq + " beyond").isEmpty();
            assertThat(beyond.totalRowCount()).as(sq + " beyond").isEqualTo(full.size());
        }
    }

    private HighLevelPage<HighLevelKey> readHighLevel(HighLevelQuery hq, ReadQuery q, long page, int pageSize) {
        return reader.readHighLevel(hq, q.highLevelRequest(AggregationLevel.L1, page, pageSize), (tx, keys) -> keys);
    }

    @Provide
    Arbitrary<List<ProjectionTransaction>> highLevelHistory() {
        return ReadGenerators.highLevelHistory();
    }

    private ProjectionPage read(Level0Query lq, ReadQuery q, long page, int pageSize) {
        return reader.readLevel0(lq, q.request(page, pageSize));
    }

    @Provide
    Arbitrary<List<ProjectionTransaction>> history() {
        return ReadGenerators.history();
    }

    @Provide
    Arbitrary<List<SizedQuery>> sizedQueries() {
        return Combinators.combine(ReadGenerators.queries(), Arbitraries.integers().between(1, 20))
                .as(SizedQuery::new)
                .list().ofSize(10);
    }
}
