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
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

/** The full projection transaction: projected transactions, rollups, page index and counters together. */
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
    void one_batch_populates_transactions_rollups_page_index_and_counters() {
        writer.apply(new ProjectionBatch("s", List.of(
                tx("t1", "b1", "p1"), tx("t2", "b1", "p1"), tx("t3", "b1", "p2")), 1L));

        assertThat(count(wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS)).isEqualTo(3);
        assertThat(count("wqPrjSession")).isEqualTo(1);
        assertThat(count("wqPrjPlay")).isEqualTo(2);
        // One high-level row per view for the single session.
        assertThat(count(ProjectionInvariants.WEB_TIMELINE)).isEqualTo(1);
        assertThat(count(ProjectionInvariants.CC_TIMELINE)).isEqualTo(1);
    }

    @Test
    void transaction_counters_match_transaction_rows_and_high_level_counters_match_page_rows() {
        writer.apply(new ProjectionBatch("s", List.of(
                tx("t1", "b1", "p1"), tx("t2", "b1", "p1")), 1L));

        assertThat(sentinel(0)).isEqualTo(2);   // TRANSACTION
        assertThat(sentinel(1)).isEqualTo(1);   // HIGH_LEVEL -- one session
    }

    @Test
    void an_unsessioned_transaction_is_its_own_page_unit() {
        ProjectionTransaction payment = new ProjectionTransaction(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null);

        writer.apply(new ProjectionBatch("s", List.of(payment), 1L));

        assertThat(count(ProjectionInvariants.CC_TIMELINE)).isEqualTo(1);
        assertThat(count("wqPrjSession")).isZero();
    }

    @Test
    void replayed_transactions_do_not_double_count_anything() {
        ProjectionBatch batch = new ProjectionBatch("s", List.of(tx("t1", "b1", "p1")), 1L);
        writer.apply(batch);

        writer.apply(batch);

        assertThat(count(wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS)).isEqualTo(1);
        assertThat(sentinel(0)).isEqualTo(1);
        assertThat(sessionTxCount()).isEqualTo(1);
    }

    @Test
    void a_cancellation_transaction_is_counted_as_an_transaction_row_but_creates_no_page_unit() {
        ProjectionTransaction cancel = new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "c1", T0, "P11", "instants", "b1", "p1",
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "t1", null);

        writer.apply(new ProjectionBatch("s", List.of(cancel), 1L));

        // It is a row in both transaction tables, so P-2 requires it to be counted at TRANSACTION level.
        assertThat(count(wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS)).isEqualTo(1);
        assertThat(sentinel(0)).isEqualTo(1);
        assertThat(count(ProjectionInvariants.WEB_TIMELINE)).isZero();
        assertThat(sentinel(1)).isZero();
        assertThat(count("wqPrjSession")).isZero();
    }

    /**
     * Oracle check (P-2 shape): after an out-of-order, mixed-source batch, every view's total
     * equals a live COUNT over the projection, per month. The session's TRANSACTIONs arrive
     * latest-first, so its entry re-anchors twice -- the second time across a month boundary -- and
     * the counts must follow it.
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

        // The session row now sits at its earliest transaction, in October, in both views.
        assertThat(count(ProjectionInvariants.WEB_TIMELINE)).isEqualTo(1);
        assertThat(count(ProjectionInvariants.CC_TIMELINE)).isEqualTo(2);
        for (int view : List.of(1, 2)) {
            assertThat(sentinelsByMonth(view, 1))
                    .as("ENTRY total vs entries, view %d", view)
                    .isEqualTo(liveByMonth(view == 1 ? ProjectionInvariants.WEB_TIMELINE : ProjectionInvariants.CC_TIMELINE,
                            "occurredAt"));
            assertThat(sentinelsByMonth(view, 0))
                    .as("TX total vs transaction rows, view %d", view)
                    .isEqualTo(liveByMonth(view == 1 ? ProjectionInvariants.WEB_TRANSACTIONS : "wqPrjTransaction",
                            "occurredAt"));
        }
        // October holds the session, November only the payment.
        assertThat(monthSentinel(2, 1, "2034-10-01")).isEqualTo(1);
        assertThat(monthSentinel(2, 1, "2034-11-01")).isEqualTo(1);
        assertThat(monthSentinel(1, 1, "2034-11-01")).isZero();
        assertEveryCounterMatchesTheProjection();
    }

    /**
     * Plan D5 end to end: a late, earlier transaction re-anchors the session IN PLACE. Its one
     * "wqPrjSession" row gets the new "startedAt", its entry moves to that instant on both
     * endpoints, and its ENTRY count moves from the old month to the new one. The totals do not
     * change: still one session entry, and the rollup sums every transaction.
     */
    @Test
    void a_late_earlier_transaction_re_anchors_the_session_its_timeline_row_and_counters() {
        Instant oct30 = Instant.parse("2034-10-30T08:00:00Z");
        writer.apply(new ProjectionBatch("s", List.of(
                txProduct("r1", "b5", "p1", T0, 11),
                txProduct("r2", "b5", "p2", T0.plusSeconds(10), 11)), 1L));
        assertThat(monthSentinel(2, 1, "2034-11-01")).isEqualTo(1);

        writer.apply(new ProjectionBatch("s", List.of(txProduct("r0", "b5", "p1", oct30, 11)), 2L));

        assertThat(db().value("select count(*)::int from \"wqPrjSession\"", Integer.class)).isEqualTo(1);
        assertThat(db().value("select \"startedAt\" from \"wqPrjSession\"", Instant.class)).isEqualTo(oct30);
        assertThat(db().value("select \"txCount\" from \"wqPrjSession\"", Integer.class)).isEqualTo(3);
        assertThat(db().value("select \"totalPlays\" from \"wqPrjSession\"", BigDecimal.class))
                .isEqualByComparingTo("15.0000");
        assertThat(db().value("select \"startedAt\" from \"wqPrjPlay\" where \"subBatchId\" = 'p1'", Instant.class))
                .isEqualTo(oct30);
        for (String timeline : List.of(ProjectionInvariants.WEB_TIMELINE, ProjectionInvariants.CC_TIMELINE)) {
            assertThat(db().column("select \"occurredAt\" from " + ProjectionInvariants.relation(timeline)
                    + " where \"kind\" = 0", Instant.class)).as(timeline).containsExactly(oct30);
        }
        for (int view : List.of(1, 2)) {
            assertThat(monthSentinel(view, 1, "2034-11-01")).as("view %d, old month", view).isZero();
            assertThat(monthSentinel(view, 1, "2034-10-01")).as("view %d, new month", view).isEqualTo(1);
            assertThat(productMonthCount(view, 1, 11, T0)).as("view %d, old month", view).isZero();
            assertThat(productMonthCount(view, 1, 11, oct30)).as("view %d, new month", view).isEqualTo(1);
        }
        assertEveryCounterMatchesTheProjection();
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    /**
     * Oracle check for product changes: session b2's key (start, LEAST(productType)) depends on
     * arrival order. It opens with no product, is raised to 11 in place, lowered to 7 in place by
     * a claimable prize (which materialises nothing new: both views already hold the row), then
     * re-anchored into the previous month by a TRANSACTION at product 5. Per-product ENTRY rows must
     * follow every step, not only the all-products total; the prize also moves the entry to its own
     * first source.
     */
    @Test
    void per_product_counters_follow_a_session_whose_product_changes_with_arrival_order() {
        Instant monthBefore = Instant.parse("2034-10-30T11:00:00Z");
        writer.apply(new ProjectionBatch("s", List.of(
                txProduct("u1", "b2", "p1", T0, null),
                txProduct("u2", "b2", "p2", T0.plusSeconds(10), 11),
                // Ties u1 at the session's start with a lower sourceId: the first transaction (D6).
                prize("a1", "b2", T0, 7)), 1L));

        // Product-only changes: still one row per view, now at product 7.
        assertThat(highLevelProducts(ProjectionInvariants.WEB_TIMELINE)).containsExactly(Products.id(7));
        assertThat(highLevelProducts(ProjectionInvariants.CC_TIMELINE)).containsExactly(Products.id(7));
        assertThat(productMonthCount(1, 1, 7, T0)).isEqualTo(1);
        assertThat(productMonthCount(1, 1, 11, T0)).isZero();
        assertThat(db().value("select \"firstSource\" from \"wqPrjSession\" where \"batchId\" = 'b2'", Short.class))
                .isEqualTo((short) SourceKind.CLAIMABLE_PRIZE.code());
        assertThat(db().value("select \"source\" from \"wqOwnerMonth\" where \"unit\" = 1", Short.class))
                .as("the entry follows its first source").isEqualTo((short) SourceKind.CLAIMABLE_PRIZE.code());
        assertEveryCounterMatchesTheProjection();

        writer.apply(new ProjectionBatch("s", List.of(
                txProduct("u0", "b2", "p0", monthBefore, 5)), 2L));

        // Start AND product moved: the old (November, 7) row is gone, (October, 5) holds the entry.
        assertThat(highLevelProducts(ProjectionInvariants.CC_TIMELINE)).containsExactly(Products.id(5));
        assertThat(productMonthCount(2, 1, 7, T0)).isZero();
        assertThat(productMonthCount(2, 1, 5, monthBefore)).isEqualTo(1);
        assertThat(productMonthCount(2, 1, 0, T0)).isZero();
        assertThat(productMonthCount(2, 1, 0, monthBefore)).isEqualTo(1);
        assertEveryCounterMatchesTheProjection();
    }

    /**
     * R98 / D6: a session's page rows carry the SESSION's currency in every view, whatever order
     * its transactions arrive in. t1 (a transaction) and a1 (a claimable prize) each open the session's
     * row first depending on order; t0 (a day earlier) re-anchors it, so the row is also moved. Every
     * order leaves both views' rows at USD, identical, and agreeing with the session. A later
     * transaction in another currency is dead-lettered and changes nothing (single-currency session).
     */
    @Test
    void session_page_rows_carry_the_session_currency_in_every_view_in_any_arrival_order() {
        Instant dayBefore = T0.minus(1, java.time.temporal.ChronoUnit.DAYS);
        List<ProjectionTransaction> transactions = List.of(
                txCurrency("t1", T0, "USD"),
                new ProjectionTransaction(1L, SourceKind.CLAIMABLE_PRIZE, "a1", T0.plusSeconds(10), "P11",
                        "instants", "b9", null, TxTypes.NEUTRAL, new BigDecimal("5.0000"), "USD", null, null, null),
                txCurrency("t0", dayBefore, "USD"));

        List<String> expected = null;
        for (List<ProjectionTransaction> order : permutations(transactions)) {
            clearProjection();
            long offset = 0;
            for (ProjectionTransaction transaction : order) {
                writer.apply(new ProjectionBatch("s", List.of(transaction), ++offset));
            }
            List<String> rows = sessionPageRows();
            String described = order.stream().map(ProjectionTransaction::sourceId).toList().toString();
            assertThat(rows).as(described).hasSize(2).allMatch(row -> row.contains("USD"));
            String sessionCurrency = db().value("select \"currency\" from \"wqPrjSession\"", String.class);
            assertThat(sessionCurrency).as(described).isEqualTo("USD");
            assertThat(new ProjectionInvariants(jdbc()).drift()).as(described).isEmpty();
            if (expected == null) {
                expected = rows;
            } else {
                assertThat(rows).as(described).isEqualTo(expected);
            }
        }

        WriteResult mismatch = writer.apply(new ProjectionBatch("s", List.of(
                new ProjectionTransaction(1L, SourceKind.CLAIMABLE_PRIZE, "a2", dayBefore.minusSeconds(5), "P11",
                        "instants", "b9", null, TxTypes.NEUTRAL, new BigDecimal("5.0000"), "AUD", null, null, null)),
                9L));
        assertThat(mismatch.deadLettered()).isEqualTo(1);
        assertThat(sessionPageRows()).isEqualTo(expected);
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    private List<String> sessionPageRows() {
        return db().maps("""
                select 'web' as v, "occurredAt", "productId", "productType", "currency", "visibleExclCancelled"
                from %s web where "kind" = 0
                union all
                select 'cc', "occurredAt", "productId", "productType", "currency", "visibleExclCancelled"
                from %s cc where "kind" = 0
                order by 1""".formatted(ProjectionInvariants.WEB_TIMELINE, ProjectionInvariants.CC_TIMELINE))
                .stream().map(r -> r.values().toString()).toList();
    }

    private ProjectionTransaction txCurrency(String id, Instant at, String currency) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, at, "P11", "instants", "b9", "b9-p",
                TxTypes.PLAY, new BigDecimal("5.0000"), currency, null, null, null);
    }

    private static <T> List<List<T>> permutations(List<T> transactions) {
        if (transactions.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new java.util.ArrayList<>();
        for (int i = 0; i < transactions.size(); i++) {
            List<T> rest = new java.util.ArrayList<>(transactions);
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
        return db().column("select \"productId\" from " + ProjectionInvariants.relation(table) + " where \"kind\" = 0", String.class);
    }

    /** The day counter at test product {@code product}'s type; 0 reads the ALL sentinel ''. */
    /** The view's "cnt" of one unit in the UTC month of {@code at}, for one product type (0: every one). */
    private int productMonthCount(int view, int unit, int product, Instant at) {
        return db().value("""
                select coalesce(sum("cnt"),0)::int from "wqOwnerMonth"
                where "unit" = ? and "source" = any(?) and not "zeroAmount"
                  and (? or "productType" = ?)
                  and "month" = date_trunc('month', ?::timestamptz at time zone 'UTC')::date
                """, Integer.class, (short) unit, sources(view), product == 0,
                product == 0 ? "" : Products.type(product), at.toString());
    }

    private static Integer[] sources(int view) {
        return view == 1 ? new Integer[] {1, 3} : new Integer[] {1, 2, 3, 4, 5};
    }

    private ProjectionTransaction tx(String id, String batchId, String subBatchId) {
        return txAt(id, batchId, subBatchId, T0);
    }

    private ProjectionTransaction txAt(String id, String batchId, String subBatchId, Instant at) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, at, "P11", "instants", batchId, subBatchId,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction txProduct(
            String id, String batchId, String subBatchId, Instant at, Integer product) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, at,
                product == null ? null : Products.id(product),
                product == null ? null : Products.type(product), batchId, subBatchId,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction prize(String id, String batchId, Instant at, int product) {
        return new ProjectionTransaction(
                1L, SourceKind.CLAIMABLE_PRIZE, id, at, Products.id(product), Products.type(product),
                batchId, null, TxTypes.WIN, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction ccOnlyPayment(String id, Instant at) {
        return new ProjectionTransaction(
                1L, SourceKind.PAYMENT, id, at, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return db().value("select count(*)::int from " + ProjectionInvariants.relation(table), Integer.class);
    }

    /** /web's total "cnt" of one unit (TX 0, ENTRY 1) over every month and combination. */
    private int sentinel(int unit) {
        return db().value("""
                select coalesce(sum("cnt"),0)::int from "wqOwnerMonth"
                where "unit" = ? and "source" = any(?) and not "zeroAmount"
                """, Integer.class, (short) unit, sources(1));
    }

    /** The view's total "cnt" of one unit in one UTC month (its first day). */
    private int monthSentinel(int view, int unit, String month) {
        return db().value("""
                select coalesce(sum("cnt"),0)::int from "wqOwnerMonth"
                where "unit" = ? and "source" = any(?) and not "zeroAmount" and "month" = ?::date
                """, Integer.class, (short) unit, sources(view), month);
    }

    /** Non-zero totals per UTC month for one view and unit. */
    private Map<String, Integer> sentinelsByMonth(int view, int unit) {
        return toMap(db().rows("""
                select "month"::text as d, sum("cnt")::int as n from "wqOwnerMonth"
                where "userId" = 1 and "unit" = ? and "source" = any(?) and not "zeroAmount"
                group by 1 having sum("cnt") > 0
                """, (short) unit, sources(view)));
    }

    /** The oracle: actual rows per UTC month. */
    private Map<String, Integer> liveByMonth(String table, String column) {
        return toMap(db().rows("""
                select date_trunc('month', "%s" at time zone 'UTC')::date::text as d, count(*)::int as n
                from %s where "userId" = 1 group by 1
                """.formatted(column, ProjectionInvariants.relation(table))));
    }

    private static Map<String, Integer> toMap(List<wqs.db.TestSql.Row> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.get("d", String.class), r -> r.get("n", Integer.class)));
    }

    private int sessionTxCount() {
        return db().value("select \"txCount\" from \"wqPrjSession\"", Integer.class);
    }
}
