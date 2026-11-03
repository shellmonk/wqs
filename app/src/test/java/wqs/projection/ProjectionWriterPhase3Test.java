package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

/** The full projection transaction: items, rollups, page index and counters together. */
@SpringBootTest
class ProjectionWriterPhase3Test extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-11-04T11:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        // October and November 2034: the oracle test re-anchors a session across a month boundary.
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 1);
        clearProjection();
    }

    @Test
    void one_batch_populates_items_rollups_page_index_and_counters() {
        writer.apply(new ProjectionBatch("s", List.of(
                tx("t1", "b1", "p1"), tx("t2", "b1", "p1"), tx("t3", "b1", "p2")), 1L));

        assertThat(count("wqWebItem")).isEqualTo(3);
        assertThat(count("wqSession")).isEqualTo(1);
        assertThat(count("wqPlay")).isEqualTo(2);
        // One high-level row per view for the single session.
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
    }

    @Test
    void item_counters_match_item_rows_and_high_level_counters_match_page_rows() {
        writer.apply(new ProjectionBatch("s", List.of(
                tx("t1", "b1", "p1"), tx("t2", "b1", "p1")), 1L));

        assertThat(sentinel("wqCountDay", 0)).isEqualTo(2);   // ITEM
        assertThat(sentinel("wqCountDay", 1)).isEqualTo(1);   // HIGH_LEVEL -- one session
    }

    @Test
    void an_unsessioned_item_is_its_own_page_unit() {
        ProjectionItem payment = new ProjectionItem(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null);

        writer.apply(new ProjectionBatch("s", List.of(payment), 1L));

        assertThat(count("wqCcHighLevel")).isEqualTo(1);
        assertThat(count("wqSession")).isZero();
    }

    @Test
    void replayed_items_do_not_double_count_anything() {
        ProjectionBatch batch = new ProjectionBatch("s", List.of(tx("t1", "b1", "p1")), 1L);
        writer.apply(batch);

        writer.apply(batch);

        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(sentinel("wqCountDay", 0)).isEqualTo(1);
        assertThat(sessionTxCount()).isEqualTo(1);
    }

    @Test
    void a_cancellation_item_is_counted_as_an_item_row_but_creates_no_page_unit() {
        ProjectionItem cancel = new ProjectionItem(
                1L, SourceKind.TRANSACTION, "c1", T0, "P11", "instants", "b1", "p1",
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "t1", null);

        writer.apply(new ProjectionBatch("s", List.of(cancel), 1L));

        // It is a row in both item tables, so P-2 requires it to be counted at ITEM level.
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(sentinel("wqCountDay", 0)).isEqualTo(1);
        assertThat(count("wqWebHighLevel")).isZero();
        assertThat(sentinel("wqCountDay", 1)).isZero();
        assertThat(count("wqSession")).isZero();
    }

    /**
     * Oracle check (P-2 shape): after an out-of-order, mixed-source batch, every sentinel counter
     * equals a live COUNT over the projection, per view and per day. The session's TRANSACTIONs
     * arrive latest-first, so its page-index row re-anchors twice -- the second time across a day
     * AND a month boundary -- and the counters must follow it.
     */
    @Test
    void sentinel_counters_equal_live_counts_after_an_out_of_order_mixed_source_batch() {
        Instant nov1 = Instant.parse("2034-11-01T00:30:00Z");
        Instant oct31 = Instant.parse("2034-10-31T23:30:00Z");
        writer.apply(new ProjectionBatch("s", List.of(
                txAt("t3", "b1", "p2", T0),
                txAt("t2", "b1", "p1", nov1),
                ccOnlyPayment("pay1", T0.plusSeconds(60)),
                txAt("t1", "b1", "p1", oct31)), 1L));

        // The session row now sits at its earliest item, in October, in both views.
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(2);
        for (int view : List.of(1, 2)) {
            assertThat(sentinelsByDay(view, 1))
                    .as("HIGH_LEVEL sentinel vs page-index rows, view %d", view)
                    .isEqualTo(liveByDay(view == 1 ? "wqWebHighLevel" : "wqCcHighLevel",
                            "occurredAt"));
            assertThat(sentinelsByDay(view, 0))
                    .as("ITEM sentinel vs item rows, view %d", view)
                    .isEqualTo(liveByDay(view == 1 ? "wqWebItem" : "wqCcItem", "occurredAt"));
        }
        // The month tier agrees too: October holds the session, November only the payment.
        assertThat(monthSentinel(2, 1, "2034-10-01")).isEqualTo(1);
        assertThat(monthSentinel(2, 1, "2034-11-01")).isEqualTo(1);
        assertThat(monthSentinel(1, 1, "2034-11-01")).isZero();
        assertEveryCounterMatchesTheProjection();
    }

    /**
     * Oracle check for product changes: session b2's key (start, LEAST(productType)) depends on
     * arrival order. It opens with no product, is raised to 11 in place, lowered to 7 in place by
     * a /cc-only item (which also materialises nothing new: /cc already holds the row), then
     * re-anchored a day earlier by a TRANSACTION at product 5. Per-product HIGH_LEVEL rows must
     * follow every step, not only the all-products sentinel.
     */
    @Test
    void per_product_counters_follow_a_session_whose_product_changes_with_arrival_order() {
        Instant dayBefore = T0.minus(1, java.time.temporal.ChronoUnit.DAYS);
        writer.apply(new ProjectionBatch("s", List.of(
                txProduct("u1", "b2", "p1", T0, null),
                txProduct("u2", "b2", "p2", T0.plusSeconds(10), 11),
                // Ties u1 at the session's start with a lower sourceId: the first item (D6).
                ccOnlyAdjustment("a1", "b2", T0, 7)), 1L));

        // Product-only changes: still one row per view, now at product 7.
        assertThat(highLevelProducts("wqWebHighLevel")).containsExactly(Products.id(7));
        assertThat(highLevelProducts("wqCcHighLevel")).containsExactly(Products.id(7));
        assertThat(productDayCount(1, 1, 7, T0)).isEqualTo(1);
        assertThat(productDayCount(1, 1, 11, T0)).isZero();
        assertEveryCounterMatchesTheProjection();

        writer.apply(new ProjectionBatch("s", List.of(
                txProduct("u0", "b2", "p0", dayBefore, 5)), 2L));

        // Start AND product moved: the old (T0, 7) rows are empty, (T0 - 1 day, 5) holds the unit.
        assertThat(highLevelProducts("wqCcHighLevel")).containsExactly(Products.id(5));
        assertThat(productDayCount(2, 1, 7, T0)).isZero();
        assertThat(productDayCount(2, 1, 5, dayBefore)).isEqualTo(1);
        assertThat(productDayCount(2, 1, 0, T0)).isZero();
        assertThat(productDayCount(2, 1, 0, dayBefore)).isEqualTo(1);
        assertEveryCounterMatchesTheProjection();
    }

    /**
     * R98 / D6: a session's page rows carry the SESSION's currency in every view, whatever order
     * its items arrive in. t1 (both views) and a1 (/cc only) each open the session's row in a view
     * first depending on order; t0 (a day earlier) re-anchors it, so the row is also moved. Every
     * order leaves both views' rows at USD, identical, and agreeing with the session. A later
     * item in another currency is dead-lettered and changes nothing (single-currency session).
     */
    @Test
    void session_page_rows_carry_the_session_currency_in_every_view_in_any_arrival_order() {
        Instant dayBefore = T0.minus(1, java.time.temporal.ChronoUnit.DAYS);
        List<ProjectionItem> items = List.of(
                txCurrency("t1", T0, "USD"),
                new ProjectionItem(1L, SourceKind.MANUAL_ADJUSTMENT, "a1", T0.plusSeconds(10), "P11",
                        "instants", "b9", null, TxTypes.NEUTRAL, new BigDecimal("5.0000"), "USD", null, null, null),
                txCurrency("t0", dayBefore, "USD"));

        List<String> expected = null;
        for (List<ProjectionItem> order : permutations(items)) {
            clearProjection();
            long offset = 0;
            for (ProjectionItem item : order) {
                writer.apply(new ProjectionBatch("s", List.of(item), ++offset));
            }
            List<String> rows = sessionPageRows();
            String described = order.stream().map(ProjectionItem::sourceId).toList().toString();
            assertThat(rows).as(described).hasSize(2).allMatch(row -> row.contains("USD"));
            String sessionCurrency = db().value("select \"currency\" from \"wqSession\"", String.class);
            assertThat(sessionCurrency).as(described).isEqualTo("USD");
            assertThat(new ProjectionInvariants(jdbc()).drift()).as(described).isEmpty();
            if (expected == null) {
                expected = rows;
            } else {
                assertThat(rows).as(described).isEqualTo(expected);
            }
        }

        WriteResult mismatch = writer.apply(new ProjectionBatch("s", List.of(
                new ProjectionItem(1L, SourceKind.MANUAL_ADJUSTMENT, "a2", dayBefore.minusSeconds(5), "P11",
                        "instants", "b9", null, TxTypes.NEUTRAL, new BigDecimal("5.0000"), "AUD", null, null, null)),
                9L));
        assertThat(mismatch.deadLettered()).isEqualTo(1);
        assertThat(sessionPageRows()).isEqualTo(expected);
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    private List<String> sessionPageRows() {
        return db().maps("""
                select 'web' as v, "occurredAt", "productId", "productType", "currency", "visibleExclCancelled"
                from "wqWebHighLevel" where "kind" = 0
                union all
                select 'cc', "occurredAt", "productId", "productType", "currency", "visibleExclCancelled"
                from "wqCcHighLevel" where "kind" = 0
                order by 1""").stream().map(r -> r.values().toString()).toList();
    }

    private ProjectionItem txCurrency(String id, Instant at, String currency) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, at, "P11", "instants", "b9", "b9-p",
                TxTypes.PLAY, new BigDecimal("5.0000"), currency, null, null, null);
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new java.util.ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> order = new java.util.ArrayList<>();
                order.add(head);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }

    /** P-2 over the whole projection, via the shared oracle. */
    private void assertEveryCounterMatchesTheProjection() {
        assertThat(new ProjectionInvariants(jdbc()).counterDrift()).isEmpty();
    }

    private List<String> highLevelProducts(String table) {
        return db().column("select \"productId\" from \"" + table + "\" where \"kind\" = 0", String.class);
    }

    /** The day counter at test product {@code product}'s type; 0 reads the ALL sentinel ''. */
    private int productDayCount(int view, int pageUnit, int product, Instant at) {
        return db().value("""
                select coalesce(sum("cntAll"),0)::int from "wqCountDay"
                where "viewKind" = ? and "pageUnit" = ? and "productType" = ? and "typeId" = 0
                  and "period" = (?::timestamptz at time zone 'UTC')::date
                """, Integer.class, (short) view, (short) pageUnit, product == 0 ? "" : Products.type(product),
                at.toString());
    }

    private ProjectionItem tx(String id, String batchId, String subBatchId) {
        return txAt(id, batchId, subBatchId, T0);
    }

    private ProjectionItem txAt(String id, String batchId, String subBatchId, Instant at) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, at, "P11", "instants", batchId, subBatchId,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionItem txProduct(
            String id, String batchId, String subBatchId, Instant at, Integer product) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, at,
                product == null ? null : Products.id(product),
                product == null ? null : Products.type(product), batchId, subBatchId,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionItem ccOnlyAdjustment(String id, String batchId, Instant at, int product) {
        return new ProjectionItem(
                1L, SourceKind.MANUAL_ADJUSTMENT, id, at, Products.id(product), Products.type(product),
                batchId, null, TxTypes.NEUTRAL, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionItem ccOnlyPayment(String id, Instant at) {
        return new ProjectionItem(
                1L, SourceKind.PAYMENT, id, at, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return db().value("select count(*)::int from \"" + table + "\"", Integer.class);
    }

    private int sentinel(String table, int pageUnit) {
        return db().value("""
                select coalesce(sum("cntAll"),0)::int from "%s"
                where "viewKind" = 1 and "pageUnit" = ? and "productType" = '' and "typeId" = 0
                """.formatted(table), Integer.class, (short) pageUnit);
    }

    private int monthSentinel(int view, int pageUnit, String period) {
        return db().value("""
                select coalesce(sum("cntAll"),0)::int from "wqCountMonth"
                where "viewKind" = ? and "pageUnit" = ? and "productType" = '' and "typeId" = 0
                  and "period" = ?::date
                """, Integer.class, (short) view, (short) pageUnit, period);
    }

    /** Non-zero day-tier sentinel cntAll per UTC day for one view and page unit. */
    private Map<String, Integer> sentinelsByDay(int view, int pageUnit) {
        return toMap(db().rows("""
                select "period"::text as d, "cntAll"::int as n from "wqCountDay"
                where "userId" = 1 and "viewKind" = ? and "pageUnit" = ? and "productType" = '' and "typeId" = 0
                  and "cntAll" > 0
                """, (short) view, (short) pageUnit));
    }

    /** The oracle: actual rows per UTC day. */
    private Map<String, Integer> liveByDay(String table, String column) {
        return toMap(db().rows("""
                select ("%s" at time zone 'UTC')::date::text as d, count(*)::int as n
                from "%s" where "userId" = 1 group by 1
                """.formatted(column, table)));
    }

    private static Map<String, Integer> toMap(List<wqs.db.TestSql.Row> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.get("d", String.class), r -> r.get("n", Integer.class)));
    }

    private int sessionTxCount() {
        return db().value("select \"txCount\" from \"wqSession\"", Integer.class);
    }
}
