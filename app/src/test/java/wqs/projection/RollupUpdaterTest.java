package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.db.TestSql;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

class RollupUpdaterTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-05-10T10:00:00Z");

    @BeforeEach
    void setUp() {
        // April and May 2034: the month-boundary re-anchor test needs both partitions.
        new PartitionManager(jdbc()).ensurePartitionsAhead(YearMonth.of(2034, 4), 1);
        clean();
    }

    @Test
    void a_first_transaction_creates_the_session_and_the_play() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));

        Rollup session = session("b1");
        assertThat(session.txCount()).isEqualTo(1);
        assertThat(session.playCount()).isEqualTo(1);
        assertThat(session.totalPlays()).isEqualByComparingTo("5.0000");
        assertThat(session.totalWins()).isEqualByComparingTo("0.0000");
        assertThat(session.net()).isEqualByComparingTo("-5.0000");
    }

    @Test
    void later_transactions_accumulate_into_the_same_session() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(win("t2", "b1", "p1", "12.0000", T0.plusSeconds(30)));

        Rollup session = session("b1");
        assertThat(session.txCount()).isEqualTo(2);
        assertThat(session.playCount()).isEqualTo(1);
        assertThat(session.totalPlays()).isEqualByComparingTo("5.0000");
        assertThat(session.totalWins()).isEqualByComparingTo("12.0000");
        assertThat(session.net()).isEqualByComparingTo("7.0000");
    }

    @Test
    void a_second_play_increments_play_count_only_once() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(wager("t2", "b1", "p2", "5.0000", T0.plusSeconds(60)));
        apply(win("t3", "b1", "p2", "1.0000", T0.plusSeconds(70)));

        assertThat(session("b1").playCount()).isEqualTo(2);
        assertThat(session("b1").txCount()).isEqualTo(3);
    }

    @Test
    void play_level_metrics_mirror_the_session_shape() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(win("t2", "b1", "p1", "2.0000", T0.plusSeconds(10)));

        Rollup play = play("b1", "p1");
        assertThat(play.txCount()).isEqualTo(2);
        assertThat(play.totalPlays()).isEqualByComparingTo("5.0000");
        assertThat(play.totalWins()).isEqualByComparingTo("2.0000");
        assertThat(play.net()).isEqualByComparingTo("-3.0000");
    }

    @Test
    void an_earlier_transaction_arriving_second_re_anchors_the_session_and_the_play() {
        ProjectionTransaction later = wager("t1", "b1", "p1", "5.0000", T0.plusSeconds(100));
        ProjectionTransaction earlier = win("t2", "b1", "p1", "3.0000", T0);

        apply(later);
        apply(earlier);
        Map<String, List<Map<String, Object>>> outOfOrder = snapshot();

        assertThat(db().count("\"wqPrjSession\"")).isEqualTo(1);
        assertThat(db().count("\"wqPrjPlay\"")).isEqualTo(1);
        Rollup session = session("b1");
        assertThat(session.startedAt()).isEqualTo(utc(T0));
        assertThat(session.endedAt()).isEqualTo(utc(T0.plusSeconds(100)));
        assertThat(session.txCount()).isEqualTo(2);
        assertThat(session.playCount()).isEqualTo(1);
        assertThat(session.net()).isEqualByComparingTo("-2.0000");
        Rollup play = play("b1", "p1");
        assertThat(play.startedAt()).isEqualTo(utc(T0));
        assertThat(play.endedAt()).isEqualTo(utc(T0.plusSeconds(100)));
        assertThat(db().column("select \"startedAt\" from \"wqPrjGroupLocator\"", Instant.class))
                .containsExactly(T0, T0);

        clean();
        apply(earlier);
        apply(later);
        assertThat(outOfOrder).isEqualTo(snapshot());
    }

    /**
     * The re-key path (delete returning the whole row, re-insert at the new start) keeps every
     * column: numeric values at full scale, and null productId / productType / firstSource* bound
     * as typed nulls. The nullable columns are first set to null, so the re-insert
     * must carry nulls through; the re-keyed rows then equal the rows of the in-order run.
     */
    @Test
    void the_re_key_reinsert_keeps_numeric_scale_and_nullable_columns() {
        ProjectionTransaction later = transaction("t2", "b1", "p1", "1.2345", T0.plusSeconds(100), TxTypes.PLAY, null);
        ProjectionTransaction earlier = transaction("t1", "b1", "p1", "0.0001", T0, TxTypes.WIN, null);

        apply(earlier);
        apply(later);
        Map<String, List<Map<String, Object>>> inOrder = snapshot();

        clean();
        apply(later);
        for (String table : List.of("wqPrjSession", "wqPrjPlay")) {
            assertThat(db().execute("update \"" + table + "\" set \"firstSourceId\" = null, \"firstSource\" = null"))
                    .isEqualTo(1);
        }
        RollupResult rekey = apply(earlier);

        assertThat(rekey.sessionKeyChanged()).isTrue();
        assertThat(snapshot()).isEqualTo(inOrder);
        for (String table : List.of("wqPrjSession", "wqPrjPlay")) {
            TestSql.Row row = db().row("select * from \"" + table + "\"");
            assertThat(row.get("startedAt", Instant.class)).as(table).isEqualTo(T0);
            assertThat(row.get("productId")).as(table).isNull();
            assertThat(row.get("productType")).as(table).isNull();
            assertThat(row.get("firstSourceId", String.class)).as(table).isEqualTo("t1");
            assertThat(row.get("firstSource", Integer.class)).as(table).isEqualTo(SourceKind.TRANSACTION.code());
            assertThat(row.get("totalPlays", BigDecimal.class).toPlainString()).as(table).isEqualTo("1.2345");
            assertThat(row.get("totalWins", BigDecimal.class).toPlainString()).as(table).isEqualTo("0.0001");
            assertThat(row.get("net", BigDecimal.class).toPlainString()).as(table).isEqualTo("-1.2344");
            assertThat(row.get("netExclCancelled", BigDecimal.class).toPlainString()).as(table).isEqualTo("-1.2344");
        }
    }

    @Test
    void re_anchoring_moves_the_row_across_a_month_boundary() {
        Instant mayFirst = Instant.parse("2034-05-01T00:00:10Z");
        Instant aprilLast = Instant.parse("2034-04-30T23:59:50Z");

        apply(wager("t1", "b1", "p1", "5.0000", mayFirst));
        apply(wager("t2", "b1", "p1", "4.0000", aprilLast));

        assertThat(db().count("\"wqPrjSession_2034_04\"")).isEqualTo(1);
        assertThat(db().count("\"wqPrjSession_2034_05\"")).isZero();
        assertThat(db().count("\"wqPrjPlay_2034_04\"")).isEqualTo(1);
        assertThat(db().count("\"wqPrjPlay_2034_05\"")).isZero();
        Rollup session = session("b1");
        assertThat(session.startedAt()).isEqualTo(utc(aprilLast));
        assertThat(session.endedAt()).isEqualTo(utc(mayFirst));
        assertThat(session.totalPlays()).isEqualByComparingTo("9.0000");
        assertThat(session.txCount()).isEqualTo(2);
        assertThat(play("b1", "p1").startedAt()).isEqualTo(utc(aprilLast));
    }

    @Test
    void a_play_can_re_anchor_while_its_session_does_not() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(wager("t2", "b1", "p2", "5.0000", T0.plusSeconds(50)));

        RollupResult result = apply(win("t3", "b1", "p2", "1.0000", T0.plusSeconds(30)));

        assertThat(result).isEqualTo(rr(false, false, T0, 11, null, null, false));
        assertThat(play("b1", "p2").startedAt()).isEqualTo(utc(T0.plusSeconds(30)));
        assertThat(play("b1", "p2").endedAt()).isEqualTo(utc(T0.plusSeconds(50)));
        assertThat(session("b1").startedAt()).isEqualTo(utc(T0));
        assertThat(session("b1").playCount()).isEqualTo(2);
        assertThat(db().count("\"wqPrjPlay\"")).isEqualTo(2);
    }

    @Test
    void any_arrival_order_converges_to_identical_rollups() {
        List<ProjectionTransaction> chronological = List.of(
                wager("t1", "b1", "p1", "5.0000", T0, 12),
                win("t2", "b1", "p1", "7.5000", T0.plusSeconds(20)),
                wager("t3", "b1", "p2", "2.0000", T0.plusSeconds(40)),
                transaction("t4", "b1", "p2", "0.5000", T0.plusSeconds(50), TxTypes.NEUTRAL, 11),
                win("t5", "b1", "p2", "1.2500", T0.plusSeconds(80)));
        List<ProjectionTransaction> shuffled = List.of(
                chronological.get(3), chronological.get(1), chronological.get(4),
                chronological.get(0), chronological.get(2));

        chronological.forEach(this::apply);
        Map<String, List<Map<String, Object>>> inOrder = snapshot();

        clean();
        shuffled.forEach(this::apply);
        Map<String, List<Map<String, Object>>> outOfOrder = snapshot();

        assertThat(outOfOrder).isEqualTo(inOrder);
        Rollup session = session("b1");
        assertThat(session.startedAt()).isEqualTo(utc(T0));
        assertThat(session.endedAt()).isEqualTo(utc(T0.plusSeconds(80)));
        assertThat(session.txCount()).isEqualTo(5);
        assertThat(session.playCount()).isEqualTo(2);
        // t1, the first transaction, carries product 12 -- not the LEAST of the session's products.
        assertThat(session.productId()).isEqualTo(Products.id(12));
        assertThat(session.productType()).isEqualTo(Products.type(12));
        assertThat(session.totalPlays()).isEqualByComparingTo("7.0000");
        assertThat(session.totalWins()).isEqualByComparingTo("8.7500");
        assertThat(session.net()).isEqualByComparingTo("1.7500");
    }

    @Test
    void the_result_reports_new_groups_and_re_anchoring() {
        Instant start = T0.plusSeconds(100);
        assertThat(apply(wager("t1", "b1", "p1", "5.0000", start)))
                .isEqualTo(rr(true, true, start, 11, null, null, false));
        assertThat(apply(win("t2", "b1", "p1", "1.0000", T0.plusSeconds(150))))
                .isEqualTo(rr(false, false, start, 11, null, null, false));
        assertThat(apply(wager("t3", "b1", "p2", "1.0000", T0.plusSeconds(160))))
                .isEqualTo(rr(false, true, start, 11, null, null, false));
        assertThat(apply(wager("t4", "b1", "p1", "1.0000", T0)))
                .isEqualTo(rr(false, false, T0, 11, start, 11, true));
        assertThat(apply(payment())).isEqualTo(RollupResult.NONE);
    }

    @Test
    void the_session_key_reports_a_product_change_without_a_time_change() {
        apply(wager("t1", "b1", "p1", "5.0000", T0, 12));

        // Same instant, lower sourceId: t0 is the first transaction, so its product is the session's.
        assertThat(apply(wager("t0", "b1", "p1", "1.0000", T0, 11)))
                .isEqualTo(rr(false, false, T0, 11, T0, 12, true));
        // A later transaction does not displace the first transaction's product, whatever its value.
        assertThat(apply(wager("t3", "b1", "p1", "1.0000", T0.plusSeconds(20), 3)))
                .isEqualTo(rr(false, false, T0, 11, null, null, false));
    }

    @Test
    void the_session_key_reports_a_product_arriving_after_none() {
        apply(wager("t1", "b1", "p1", "5.0000", T0, null));

        // A later transaction with a product does not label a session whose first transaction has none...
        assertThat(apply(wager("t2", "b1", "p1", "1.0000", T0.plusSeconds(10), 11)))
                .isEqualTo(rr(false, false, T0, null, null, null, false));
        // ...but a new first transaction does, and a null pair is displaced like any other.
        assertThat(apply(wager("t0", "b1", "p1", "1.0000", T0, 11)))
                .isEqualTo(rr(false, false, T0, 11, T0, null, true));
    }

    @Test
    void the_session_key_reports_a_time_change_without_a_product_change() {
        apply(wager("t1", "b1", "p1", "5.0000", T0.plusSeconds(100), 11));

        assertThat(apply(wager("t2", "b1", "p1", "1.0000", T0, 11)))
                .isEqualTo(rr(false, false, T0, 11, T0.plusSeconds(100), 11, true));
    }

    @Test
    void the_session_key_reports_a_time_and_product_change_together() {
        apply(wager("t1", "b1", "p1", "5.0000", T0.plusSeconds(100), 12));

        assertThat(apply(wager("t2", "b1", "p2", "1.0000", T0, 11)))
                .isEqualTo(rr(false, true, T0, 11, T0.plusSeconds(100), 12, true));
    }

    @Test
    void the_session_key_is_unchanged_when_neither_axis_moves() {
        apply(wager("t1", "b1", "p1", "5.0000", T0, 11));

        assertThat(apply(win("t2", "b1", "p1", "1.0000", T0.plusSeconds(10))))
                .isEqualTo(rr(false, false, T0, 11, null, null, false));
    }

    /**
     * D6: the session's (productId, productType) is its FIRST transaction's pair, never a per-column
     * LEAST, which here would pair P03 with "arcade" -- a product no transaction has.
     */
    @Test
    void the_session_and_play_product_is_the_first_transactions_pair_in_any_order() {
        ProjectionTransaction first = labelled("t9", T0, "P13", "arcade");
        ProjectionTransaction later = labelled("t1", T0.plusSeconds(30), "P03", "lottery");

        apply(first);
        apply(later);
        Map<String, List<Map<String, Object>>> inOrder = snapshot();
        assertThat(pair(session("b1").productId(), session("b1").productType())).isEqualTo("P13/arcade");
        assertThat(pair(play("b1", "p1").productId(), play("b1", "p1").productType())).isEqualTo("P13/arcade");

        clean();
        apply(later);
        RollupResult reanchor = apply(first);
        assertThat(snapshot()).isEqualTo(inOrder);
        assertThat(reanchor.sessionKeyChanged()).isTrue();
        assertThat(reanchor.previousSessionProductType()).isEqualTo("lottery");
        assertThat(reanchor.sessionProductType()).isEqualTo("arcade");
    }

    /** Transactions at the same instant: the lower (sourceId, source) is first, whatever arrives first. */
    @Test
    void a_tie_on_occurred_at_is_broken_by_source_id() {
        ProjectionTransaction a = labelled("tA", T0, "P13", "lottery");
        ProjectionTransaction b = labelled("tB", T0, "P03", "arcade");

        apply(b);
        RollupResult tie = apply(a);
        Map<String, List<Map<String, Object>>> aWins = snapshot();
        assertThat(pair(session("b1").productId(), session("b1").productType())).isEqualTo("P13/lottery");
        assertThat(tie.sessionKeyChanged()).isTrue();
        assertThat(tie.previousSessionStart()).isEqualTo(T0);

        clean();
        apply(a);
        assertThat(apply(b).sessionKeyChanged()).isFalse();
        assertThat(snapshot()).isEqualTo(aWins);
    }

    private static ProjectionTransaction labelled(String id, Instant at, String productId, String productType) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, at, productId, productType, "b1", "p1",
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }

    private static String pair(String productId, String productType) {
        return productId + "/" + productType;
    }

    @Test
    void a_cancellation_touches_no_rollup() {
        ProjectionTransaction cancel = new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "tx-2", T0, "P11", "instants", "b1", "p1",
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "tx-1", null);

        assertThat(apply(cancel)).isEqualTo(RollupResult.NONE);

        assertThat(db().count("\"wqPrjSession\"")).isZero();
        assertThat(db().count("\"wqPrjPlay\"")).isZero();
        assertThat(db().count("\"wqPrjGroupLocator\"")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void a_blank_sub_batch_id_rolls_up_into_the_session_only(String blank) {
        RollupResult first = apply(transaction("t1", "b1", blank, "5.0000", T0.plusSeconds(100), TxTypes.PLAY, 11));
        RollupResult earlier = apply(transaction("t2", "b1", blank, "1.0000", T0, TxTypes.PLAY, 11));

        assertThat(first).isEqualTo(
                rr(true, false, T0.plusSeconds(100), 11, null, null, false));
        assertThat(earlier).isEqualTo(
                rr(false, false, T0, 11, T0.plusSeconds(100), 11, true));
        assertThat(db().count("\"wqPrjSession\"")).isEqualTo(1);
        assertThat(db().count("\"wqPrjPlay\"")).isZero();
        Rollup session = session("b1");
        assertThat(session.startedAt()).isEqualTo(utc(T0));
        assertThat(session.txCount()).isEqualTo(2);
        assertThat(session.playCount()).isZero();
        assertThat(db().rows("select * from \"wqPrjGroupLocator\""))
                .singleElement()
                .satisfies(locator -> {
                    assertThat(locator.get("subBatchId", String.class)).isEmpty();
                    assertThat(locator.get("startedAt", Instant.class)).isEqualTo(T0);
                });
    }

    @Test
    void an_unsessioned_transaction_creates_no_rollup() {
        apply(payment());

        assertThat(db().count("\"wqPrjSession\"")).isZero();
        assertThat(db().count("\"wqPrjGroupLocator\"")).isZero();
    }

    private RollupResult apply(ProjectionTransaction transaction) {
        return inTx().execute(status -> new RollupUpdater(jdbc()).applyTransaction(transaction));
    }

    private void clean() {
        db().execute("delete from \"wqPrjGroupLocator\"");
        db().execute("delete from \"wqPrjPlay\"");
        db().execute("delete from \"wqPrjSession\"");
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of(
                "session", db().maps("select * from \"wqPrjSession\" order by \"userId\", \"batchId\""),
                "play", db().maps("select * from \"wqPrjPlay\" order by \"userId\", \"batchId\", \"subBatchId\""),
                "locator", db().maps(
                        "select * from \"wqPrjGroupLocator\" order by \"userId\", \"batchId\", \"subBatchId\""));
    }

    private static ProjectionTransaction payment() {
        return new ProjectionTransaction(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("50.0000"), "EUR", null, null, null);
    }

    private static ProjectionTransaction wager(String id, String batch, String play, String amount, Instant at) {
        return transaction(id, batch, play, amount, at, TxTypes.PLAY, 11);
    }

    private static ProjectionTransaction wager(
            String id, String batch, String play, String amount, Instant at, Integer productId) {
        return transaction(id, batch, play, amount, at, TxTypes.PLAY, productId);
    }

    private static ProjectionTransaction win(String id, String batch, String play, String amount, Instant at) {
        return transaction(id, batch, play, amount, at, TxTypes.WIN, 11);
    }

    private static ProjectionTransaction transaction(
            String id, String batch, String play, String amount, Instant at, String type,
            Integer productId) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, at,
                productId == null ? null : Products.id(productId),
                productId == null ? null : Products.type(productId), batch, play,
                type, new BigDecimal(amount), "EUR", null, null, null);
    }

    /** A RollupResult for test product numbers (see {@link Products}); null is no product. */
    private static RollupResult rr(boolean sessionIsNew, boolean playIsNew, Instant start,
                                   Integer product, Instant previousStart, Integer previousProduct,
                                   boolean keyChanged) {
        return new RollupResult(sessionIsNew, playIsNew, start,
                product == null ? null : Products.id(product),
                product == null ? null : Products.type(product), "EUR", previousStart,
                previousProduct == null ? null : Products.type(previousProduct), keyChanged);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** The rollup columns these tests read, from one "wqPrjSession" or "wqPrjPlay" row. */
    private record Rollup(OffsetDateTime startedAt, OffsetDateTime endedAt, Integer txCount, Integer playCount,
                          BigDecimal totalPlays, BigDecimal totalWins, BigDecimal net,
                          String productId, String productType) {

        static Rollup of(TestSql.Row r, boolean session) {
            return new Rollup(r.get("startedAt", OffsetDateTime.class), r.get("endedAt", OffsetDateTime.class),
                    r.get("txCount", Integer.class), session ? r.get("playCount", Integer.class) : null,
                    r.get("totalPlays", BigDecimal.class), r.get("totalWins", BigDecimal.class),
                    r.get("net", BigDecimal.class), r.get("productId", String.class),
                    r.get("productType", String.class));
        }
    }

    private Rollup session(String batchId) {
        return Rollup.of(db().row("select * from \"wqPrjSession\" where \"batchId\" = ?", batchId), true);
    }

    private Rollup play(String batchId, String subBatchId) {
        return Rollup.of(db().row("select * from \"wqPrjPlay\" where \"batchId\" = ? and \"subBatchId\" = ?",
                batchId, subBatchId), false);
    }
}
