package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.ReadGenerators;
import wqs.gen.ReadGenerators.ReadQuery;
import wqs.gen.TxTypes;
import wqs.read.Segment.Tier;

/**
 * Property P-5 (spec 7.5, 8.1): the total assembled from per-day counters and
 * live counts of the sub-day remainders equals a live COUNT over the transaction table, for any range.
 */
@Tag("property")
@JqwikSpringSupport
@SpringBootTest
class TotalsPropertyTest extends ReadPropertyBase {

    @Property(tries = 30)
    void counter_total_equals_live_count(
            @ForAll("history") List<ProjectionTransaction> transactions,
            @ForAll("queries") List<ReadQuery> queries) {
        reset();
        applyBatches(transactions);
        for (ReadQuery q : queries) {
            long expected = oracle.count(q.view(), q.filter(), q.transactionTypes(), q.from(), q.to());
            // The reader resolves type codes itself; the envelope's total is the one clients see.
            assertThat(reader.readLevel0(queryFor(q.view()), q.request(1, 1)).totalItems())
                    .as(q.toString()).isEqualTo(expected);
            // The direct total takes the same codes as a filter (plan D2: matched as text).
            Level0Filter f = new Level0Filter(q.userId(), q.productTypes(), q.transactionTypes(), q.excludeCancelled());
            assertThat(reader.total(queryFor(q.view()), f, q.from(), q.to()))
                    .as(q.toString()).isEqualTo(expected);
        }
    }

    /**
     * P-5 at the HIGH_LEVEL page unit (levels 1-2): the total assembled from HIGH_LEVEL counters
     * -- keyed (productType, ALL types) and (ALL, ALL), never by transaction type -- and live
     * counts of the sub-day remainders equals a live COUNT over the page index, for product-type
     * subsets that include an absent value and with or without excludeCancelled
     * ("visibleExclCancelled").
     */
    @Property(tries = 30)
    void high_level_counter_total_equals_live_count(
            @ForAll("highLevelHistory") List<ProjectionTransaction> transactions,
            @ForAll("queries") List<ReadQuery> queries) {
        reset();
        applyBatches(transactions);
        for (ReadQuery q : queries) {
            HighLevelFilter f = q.highLevelFilter();
            long expected = oracle.highLevelCount(q.view(), f, q.from(), q.to());
            assertThat(reader.readHighLevel(highLevelQueryFor(q.view()),
                            q.highLevelRequest(AggregationLevel.L2, 1, 1), (tx, keys) -> keys).totalRowCount())
                    .as(q.toString()).isEqualTo(expected);
            assertThat(reader.total(highLevelQueryFor(q.view()), f, q.from(), q.to()))
                    .as(q.toString()).isEqualTo(expected);
        }
    }

    @Example
    void same_day_total_reads_no_counters() {
        reset();
        applyBatches(List.of(
                transaction("a", "2034-02-10T00:00:00Z"),
                transaction("b", "2034-02-10T09:30:00Z"),
                transaction("c", "2034-02-10T23:59:59Z")));
        Level0Filter f = new Level0Filter(1L, false);
        Instant from = Instant.parse("2034-02-10T00:00:00Z");
        Instant to = Instant.parse("2034-02-10T23:59:59Z");

        SegmentCounts sc = readerTx.execute(status ->
                reader.segmentCounts(readerJdbc, queryFor(ViewKind.WEB), f, from, to));

        assertThat(sc.segments()).extracting(Segment::tier).containsOnly(Tier.LIVE);
        assertThat(sc.counterRows()).isZero();
        assertThat(sc.total()).isEqualTo(2).isEqualTo(oracle.count(ViewKind.WEB, f, from, to));
    }

    @Example
    void exact_day_range_reads_no_live_count() {
        reset();
        applyBatches(List.of(
                transaction("a", "2034-01-01T00:00:00Z"),
                transaction("b", "2034-01-17T12:00:00Z"),
                transaction("c", "2034-02-28T23:59:59Z"),
                transaction("d", "2034-03-01T00:00:00Z")));
        Level0Filter f = new Level0Filter(1L, false);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");

        SegmentCounts sc = readerTx.execute(status ->
                reader.segmentCounts(readerJdbc, queryFor(ViewKind.WEB), f, from, to));

        assertThat(sc.segments()).extracting(Segment::tier).containsOnly(Tier.DAY);
        // One day row each for Jan 1, Jan 17 and Feb 28.
        assertThat(sc.counterRows()).isEqualTo(3);
        assertThat(sc.total()).isEqualTo(3).isEqualTo(oracle.count(ViewKind.WEB, f, from, to));
        assertThat(reader.total(queryFor(ViewKind.WEB), f, from, to)).isEqualTo(3);
    }

    /** The oracle's own row mapping must agree with the production mappers, or P-6 compares apples to pears. */
    @Example
    void oracle_page_mirrors_the_view_mappers() {
        reset();
        applyBatches(List.of(
                new ProjectionTransaction(1L, SourceKind.PAYMENT, "p", Instant.parse("2034-01-05T10:00:00Z"), null,
                        null, null, null, TxTypes.NEUTRAL, new BigDecimal("2.5000"), "USD", new BigDecimal("10.0000"), null,
                        "{\"gameCode\": \"x\"}"),
                transaction("a", "2034-01-05T10:00:00Z"),
                new ProjectionTransaction(1L, SourceKind.TRANSACTION, "a-c", Instant.parse("2034-01-06T00:00:00Z"), "P11",
                        "keno", null, null, TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, "a", null)));
        Level0Filter f = new Level0Filter(1L, false);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-02-01T00:00:00Z");
        for (ViewKind v : ViewKind.values()) {
            List<TransactionEntry> viaReader = readerTx.execute(status -> {
                var tx = readerJdbc;
                Level0Query q = queryFor(v);
                return q.fetch(tx, 1L, q.keys(tx, f, from, to, 0, 10));
            });
            assertThat(oracle.page(v, f, from, to, 0, 10)).as(v.name()).isEqualTo(viaReader)
                    .hasSize(v == ViewKind.CC ? 3 : 2);
        }
        assertThat(oracle.page(ViewKind.CC, f, from, to, 1, 1)).extracting(TransactionEntry::sourceId).containsExactly("a");
    }

    @Provide
    Arbitrary<List<ProjectionTransaction>> history() {
        return ReadGenerators.history();
    }

    @Provide
    Arbitrary<List<ReadQuery>> queries() {
        return ReadGenerators.queries().list().ofSize(10);
    }

    @Provide
    Arbitrary<List<ProjectionTransaction>> highLevelHistory() {
        return ReadGenerators.highLevelHistory();
    }

    private static ProjectionTransaction transaction(String id, String at) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, Instant.parse(at), "P11", "keno", null, null,
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
