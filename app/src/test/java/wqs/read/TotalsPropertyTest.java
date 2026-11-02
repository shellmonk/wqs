package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;
import org.jooq.impl.DSL;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.ReadGenerators;
import wqs.gen.ReadGenerators.ReadQuery;
import wqs.gen.TxTypes;
import wqs.read.Segment.Tier;

/**
 * Property P-5 (spec 7.5, 8.1): the total assembled from monthly counters, daily counters and
 * live counts of the sub-day remainders equals a live COUNT over the item table, for any range.
 */
@JqwikSpringSupport
@SpringBootTest
class TotalsPropertyTest extends ReadPropertyBase {

    @Property(tries = 30)
    void counter_total_equals_live_count(
            @ForAll("history") List<ProjectionItem> items,
            @ForAll("queries") List<ReadQuery> queries) {
        reset();
        applyBatches(items);
        for (ReadQuery q : queries) {
            assertThat(reader.total(queryFor(q.view()), q.filter(), q.from(), q.to()))
                    .as(q.toString())
                    .isEqualTo(oracle.count(q.view(), q.filter(), q.from(), q.to()));
        }
    }

    @Example
    void same_day_total_reads_no_counters() {
        reset();
        applyBatches(List.of(
                item("a", "2034-02-10T00:00:00Z"),
                item("b", "2034-02-10T09:30:00Z"),
                item("c", "2034-02-10T23:59:59Z")));
        Level0Filter f = new Level0Filter(1L, false);
        Instant from = Instant.parse("2034-02-10T00:00:00Z");
        Instant to = Instant.parse("2034-02-10T23:59:59Z");

        SegmentCounts sc = readerDsl.transactionResult(cfg ->
                reader.segmentCounts(DSL.using(cfg), queryFor(ViewKind.WEB), f, from, to));

        assertThat(sc.segments()).extracting(Segment::tier).containsOnly(Tier.LIVE);
        assertThat(sc.counterRows()).isZero();
        assertThat(sc.total()).isEqualTo(2).isEqualTo(oracle.count(ViewKind.WEB, f, from, to));
    }

    @Example
    void exact_month_range_reads_no_live_count() {
        reset();
        applyBatches(List.of(
                item("a", "2034-01-01T00:00:00Z"),
                item("b", "2034-01-17T12:00:00Z"),
                item("c", "2034-02-28T23:59:59Z"),
                item("d", "2034-03-01T00:00:00Z")));
        Level0Filter f = new Level0Filter(1L, false);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-03-01T00:00:00Z");

        SegmentCounts sc = readerDsl.transactionResult(cfg ->
                reader.segmentCounts(DSL.using(cfg), queryFor(ViewKind.WEB), f, from, to));

        assertThat(sc.segments()).extracting(Segment::tier).containsOnly(Tier.MONTH);
        assertThat(sc.counterRows()).isEqualTo(2);
        assertThat(sc.total()).isEqualTo(3).isEqualTo(oracle.count(ViewKind.WEB, f, from, to));
        assertThat(reader.total(queryFor(ViewKind.WEB), f, from, to)).isEqualTo(3);
    }

    /** The oracle's own row mapping must agree with the production mappers, or P-6 compares apples to pears. */
    @Example
    void oracle_page_mirrors_the_view_mappers() {
        reset();
        applyBatches(List.of(
                new ProjectionItem(1L, SourceKind.PAYMENT, "p", Instant.parse("2034-01-05T10:00:00Z"), null,
                        null, null, null, TxTypes.NEUTRAL, new BigDecimal("2.5000"), "USD", new BigDecimal("10.0000"), null,
                        "{\"gameCode\": \"x\"}"),
                item("a", "2034-01-05T10:00:00Z"),
                new ProjectionItem(1L, SourceKind.TRANSACTION, "a-c", Instant.parse("2034-01-06T00:00:00Z"), "P11",
                        "keno", null, null, TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, "a", null)));
        Level0Filter f = new Level0Filter(1L, false);
        Instant from = Instant.parse("2034-01-01T00:00:00Z");
        Instant to = Instant.parse("2034-02-01T00:00:00Z");
        for (ViewKind v : ViewKind.values()) {
            List<ItemEntry> viaReader = readerDsl.transactionResult(cfg -> {
                var tx = DSL.using(cfg);
                Level0Query q = queryFor(v);
                return q.fetch(tx, 1L, q.keys(tx, f, from, to, 0, 10));
            });
            assertThat(oracle.page(v, f, from, to, 0, 10)).as(v.name()).isEqualTo(viaReader)
                    .hasSize(v == ViewKind.CC ? 3 : 2);
        }
        assertThat(oracle.page(ViewKind.CC, f, from, to, 1, 1)).extracting(ItemEntry::sourceId).containsExactly("a");
    }

    @Provide
    Arbitrary<List<ProjectionItem>> history() {
        return ReadGenerators.history();
    }

    @Provide
    Arbitrary<List<ReadQuery>> queries() {
        return ReadGenerators.queries().list().ofSize(10);
    }

    private static ProjectionItem item(String id, String at) {
        return new ProjectionItem(1L, SourceKind.TRANSACTION, id, Instant.parse(at), "P11", "keno", null, null,
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
