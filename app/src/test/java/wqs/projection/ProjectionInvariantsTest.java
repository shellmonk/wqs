package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

/**
 * Negative controls for {@link ProjectionInvariants}: a projection the writer built is clean, and
 * corrupting one stored value by direct SQL makes the owning invariant report it. Without these, an
 * oracle that silently returned nothing would make P-2, P-4 and P-8 pass vacuously.
 */
@SpringBootTest
class ProjectionInvariantsTest extends ProjectionPropertyBase {

    private static final Instant T0 = Instant.parse("2034-03-31T23:00:00Z");

    private ProjectionInvariants invariants;

    @BeforeEach
    void buildACleanProjection() {
        reset();
        invariants = new ProjectionInvariants(jdbc());
        writer.apply(new ProjectionBatch("s", List.of(
                tx("t1", "p1", T0, TxTypes.PLAY, "5.0000"),
                tx("t2", "p2", T0.plusSeconds(30), TxTypes.WIN, "-7.0000"),
                // A /cc-only transaction a day earlier at a lower product: the session's key moves.
                new ProjectionTransaction(1L, SourceKind.MANUAL_ADJUSTMENT, "a1",
                        T0.minusSeconds(86_400), "P07", "bingo", "b1", null, TxTypes.NEUTRAL,
                        new BigDecimal("1.0000"), "EUR", null, null, null),
                new ProjectionTransaction(1L, SourceKind.PAYMENT, "pay1", T0.plusSeconds(3_600), null,
                        null, null, null, TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null),
                cancel(SourceKind.TRANSACTION, "c1", "t1"),
                cancel(SourceKind.PAYMENT, "pc1", "pay1")), 1L));

        assertThat(invariants.drift()).as("a projection the writer built").isEmpty();
    }

    // --- P-2 -------------------------------------------------------------------------------------

    @Test
    void a_bumped_month_tier_product_counter_is_counter_drift() {
        execute("""
                update "wqPrjCountMonth" set "cntAll" = "cntAll" + 1
                where "viewKind" = 2 and "pageUnit" = 1 and "productType" = 'bingo'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("counter mismatch").contains("tier=month", "productType=bingo");
    }

    /**
     * Ruling R1: the write path keys every HIGH_LEVEL counter at the ALL-types sentinel '', so a
     * row at a real type code is drift -- the oracle compares "type" too, not only the product type.
     */
    @Test
    void a_counter_row_at_a_real_type_code_is_counter_drift() {
        execute("""
                update "wqPrjCountMonth" set "type" = 'TT_BUY'
                where "viewKind" = 2 and "pageUnit" = 1 and "productType" = 'bingo'""");

        assertThat(invariants.counterDrift()).hasSize(2)
                .anySatisfy(d -> assertThat(d).startsWith("orphan counter row").contains(" type=TT_BUY "))
                .anySatisfy(d -> assertThat(d).startsWith("missing counter row").contains(" type= "));
    }

    @Test
    void a_stored_zero_counter_row_is_counter_drift() {
        execute("""
                insert into "wqPrjCountDay"
                values (1, 1, 1, 'ghost', '', date '2034-03-30', 0, 0)""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("orphan counter row").contains("productType=ghost");
    }

    @Test
    void a_deleted_counter_row_is_counter_drift() {
        execute("""
                delete from "wqPrjCountDay"
                where "viewKind" = 2 and "pageUnit" = 0 and "productType" = '' and "type" = ''
                  and "period" = date '2034-04-01'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("missing counter row");
    }

    @Test
    void a_changed_excl_cancelled_count_alone_is_counter_drift() {
        // /cc transactions on April 1: pay1 (cancelled), c1 and pc1 -- excl is 2 of 3.
        execute("""
                update "wqPrjCountDay" set "cntExclCancelled" = "cntExclCancelled" - 1
                where "viewKind" = 2 and "pageUnit" = 0 and "productType" = '' and "type" = ''
                  and "period" = date '2034-04-01'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("counter mismatch").contains("expectedAll=3 storedAll=3",
                        "expectedExcl=2 storedExcl=1");
    }

    // --- P-4 -------------------------------------------------------------------------------------

    @Test
    void a_play_without_transactions_is_aggregation_drift() {
        execute("""
                insert into "wqPrjPlay" ("userId", "batchId", "subBatchId", "startedAt", "endedAt",
                                      "currency")
                values (1, 'b1', 'ghost', timestamptz '2034-03-31 10:00Z',
                        timestamptz '2034-03-31 10:00Z', 'EUR')""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("orphan play row").contains("subBatchId=ghost");
    }

    @Test
    void a_deleted_session_row_is_aggregation_drift() {
        execute("""
                delete from "wqPrjSession" where "batchId" = 'b1'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("missing session row").contains("batchId=b1");
    }

    @Test
    void a_changed_session_play_count_is_aggregation_drift() {
        execute("""
                update "wqPrjSession" set "playCount" = "playCount" + 1""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_changed_session_play_count_excl_cancelled_is_aggregation_drift() {
        // p1's only transaction is cancelled, p2's is not: one surviving play.
        execute("""
                update "wqPrjSession" set "playCountExclCancelled" = "playCountExclCancelled" - 1""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_changed_session_tx_count_excl_cancelled_is_aggregation_drift() {
        execute("""
                update "wqPrjSession" set "txCountExclCancelled" = "txCountExclCancelled" + 1""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_changed_play_net_is_aggregation_drift() {
        execute("""
                update "wqPrjPlay" set "netExclCancelled" = "netExclCancelled" + 1
                where "subBatchId" = 'p2'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("play mismatch").contains("subBatchId=p2");
    }

    @Test
    void a_misplaced_group_locator_is_aggregation_drift() {
        execute("""
                update "wqPrjGroupLocator" set "startedAt" = "startedAt" + interval '1 second'
                where "subBatchId" = ''""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("group locator startedAt mismatch");
    }

    @Test
    void a_session_without_transactions_is_aggregation_drift() {
        execute("""
                insert into "wqPrjSession" ("userId", "batchId", "startedAt", "endedAt", "currency")
                values (1, 'ghost', timestamptz '2034-03-31 10:00Z', timestamptz '2034-03-31 10:00Z',
                        'EUR')""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("orphan session row").contains("batchId=ghost");
    }

    // --- P-8 -------------------------------------------------------------------------------------

    @Test
    void a_flipped_page_row_visibility_is_high_level_drift() {
        execute("""
                update "wqPrjTimeline" set "webVisibleExclCancelled" = not "webVisibleExclCancelled"
                where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("page row visibility mismatch").contains("view=WEB", "refId=b1");
    }

    @Test
    void a_session_row_missing_from_one_view_is_high_level_drift() {
        // Materialisation is per view: the session must be on /web as well as /cc.
        execute("""
                update "wqPrjTimeline" set "onWeb" = false, "webVisibleExclCancelled" = false
                where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("missing page row").contains("view=WEB", "kind=0", "refId=b1");
    }

    @Test
    void a_session_row_with_another_currency_is_high_level_drift() {
        execute("""
                update "wqPrjTimeline" set "currency" = 'USD' where "kind" = 0""");

        // One shared row: both endpoints' page indexes carry the wrong currency.
        assertThat(invariants.highLevelDrift()).hasSize(2)
                .allSatisfy(d -> assertThat(d).startsWith("page row currency mismatch")
                        .contains("expectedCurrency=EUR", "storedCurrency=USD"))
                .anySatisfy(d -> assertThat(d).contains("view=WEB"))
                .anySatisfy(d -> assertThat(d).contains("view=CC"));
    }

    /** The counter key is the session's product type, so the page row must carry the right one. */
    @Test
    void a_session_row_with_another_product_type_is_high_level_drift() {
        execute("""
                update "wqPrjTimeline" set "productType" = 'lottery' where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).hasSize(2)
                .allSatisfy(d -> assertThat(d).startsWith("page row key mismatch")
                        .contains("expectedProductType=bingo", "storedProductType=lottery"))
                .anySatisfy(d -> assertThat(d).contains("view=WEB"))
                .anySatisfy(d -> assertThat(d).contains("view=CC"));
    }

    @Test
    void a_session_with_another_product_type_is_aggregation_drift() {
        execute("""
                update "wqPrjSession" set "productType" = 'lottery'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    /**
     * D6: the oracle expects the FIRST transaction's pair. Here the per-column LEAST is (P03, arcade) --
     * no transaction's product -- while the first transaction's is (P13, arcade); a session or page row carrying
     * the LEAST id is drift.
     */
    @Test
    void a_session_labelled_by_a_per_column_least_is_drift() {
        writer.apply(new ProjectionBatch("s", List.of(
                labelled("x1", T0, "P13", "arcade"),
                labelled("x2", T0.plusSeconds(1), "P03", "lottery")), 2L));
        assertThat(invariants.drift()).as("first transaction's pair").isEmpty();

        execute("""
                update "wqPrjSession" set "productId" = 'P03' where "batchId" = 'b2'""");
        execute("""
                update "wqPrjTimeline" set "productId" = 'P03' where "refId" = 'b2'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b2");
        assertThat(invariants.highLevelDrift()).hasSize(2)
                .allSatisfy(d -> assertThat(d).startsWith("page row key mismatch")
                        .contains("refId=b2", "expectedProductId=P13", "storedProductId=P03"))
                .anySatisfy(d -> assertThat(d).contains("view=WEB"))
                .anySatisfy(d -> assertThat(d).contains("view=CC"));
    }

    private static ProjectionTransaction labelled(String id, Instant at, String productId, String productType) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, at, productId, productType, "b2", "b2-p",
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }

    @Test
    void a_page_row_at_a_stale_key_is_high_level_drift() {
        execute("""
                update "wqPrjTimeline" set "occurredAt" = "occurredAt" + interval '1 day'
                where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).hasSize(2)
                .allSatisfy(d -> assertThat(d).startsWith("page row key mismatch"))
                .anySatisfy(d -> assertThat(d).contains("view=WEB"))
                .anySatisfy(d -> assertThat(d).contains("view=CC"));
    }

    @Test
    void a_page_row_for_a_cc_only_transaction_in_web_is_high_level_drift() {
        execute("""
                update "wqPrjTimeline" set "onWeb" = true where "kind" = 4 and "refId" = 'pay1'""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("orphan page row").contains("view=WEB", "refId=pay1");
    }

    @Test
    void a_deleted_unsessioned_page_row_is_high_level_drift() {
        execute("""
                delete from "wqPrjTimeline" where "kind" = 4""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("missing page row").contains("refId=pay1");
    }

    // --- fixtures --------------------------------------------------------------------------------

    // --- cancellation (spec 6.3) --------------------------------------------------------------

    @Test
    void an_unapplied_cancel_is_cancellation_drift() {
        execute("""
                update "wqPrjTransaction" set "cancelled" = false, "cancelledBy" = null
                where "sourceId" = 't1'""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancel not applied").contains("source=1", "sourceId=t1");
    }

    @Test
    void a_wrong_cancelled_by_is_cancellation_drift() {
        execute("""
                update "wqPrjTransaction" set "cancelledBy" = 'zz'
                where "sourceId" = 'pay1'""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancelledBy mismatch").contains("source=4", "sourceId=pay1");
    }

    @Test
    void a_cancelled_flag_without_any_cancel_is_cancellation_drift() {
        execute("""
                update "wqPrjTransaction" set "cancelled" = true, "cancelledBy" = 'ghost'
                where "sourceId" = 't2'""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancelled without a cancel").contains("sourceId=t2");
    }

    /** A zero-amount cancel lives in the locator only (D8), yet still counts as t2's cancel. */
    @Test
    void a_zero_amount_cancel_in_the_locator_is_seen_by_the_cancellation_oracle() {
        execute("""
                insert into "wqPrjTransactionLocator" ("source", "sourceId", "userId", "occurredAt", "transactionId",
                    "zeroAmount", "cancels")
                values (1, 'cz', 1, timestamptz '2034-04-01 02:00:00+00', -1, true, 't2')""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancel not applied").contains("sourceId=t2");
    }

    @Test
    void a_parked_cancel_whose_target_exists_is_cancellation_drift() {
        execute("""
                insert into "wqPrjPendingCancel" ("source", "cancelSourceId", "cancelsSourceId", "userId",
                    "occurredAt", "type", "amount", "currency")
                values (1, 'c9', 't2', 1, timestamptz '2034-04-01 00:00:00+00', 'TT_CANCEL', 1, 'EUR')""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("unexpected pending cancel").contains("cancelSourceId=c9");
    }

    private void execute(String sql) {
        assertThat(db().execute(sql)).as(sql).isEqualTo(1);
    }

    private static ProjectionTransaction tx(
            String id, String subBatchId, Instant at, String type, String amount) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, at, "P11", "instants", "b1", subBatchId,
                type, new BigDecimal(amount), "EUR", null, null, null);
    }

    private static ProjectionTransaction cancel(SourceKind source, String id, String cancels) {
        return new ProjectionTransaction(1L, source, id, T0.plusSeconds(7_200), "P13", "arcade", null, null,
                TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, cancels, null);
    }
}
