package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
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
        invariants = new ProjectionInvariants(dsl());
        writer.apply(new ProjectionBatch("s", List.of(
                tx("t1", "p1", T0, TxTypes.PLAY, "5.0000"),
                tx("t2", "p2", T0.plusSeconds(30), TxTypes.WIN, "-7.0000"),
                // A /cc-only item a day earlier at a lower product: the session's key moves.
                new ProjectionItem(1L, SourceKind.MANUAL_ADJUSTMENT, "a1",
                        T0.minusSeconds(86_400), "P07", "bingo", "b1", null, TxTypes.NEUTRAL,
                        new BigDecimal("1.0000"), "EUR", null, null, null),
                new ProjectionItem(1L, SourceKind.PAYMENT, "pay1", T0.plusSeconds(3_600), null,
                        null, null, null, TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null),
                cancel(SourceKind.TRANSACTION, "c1", "t1"),
                cancel(SourceKind.PAYMENT, "pc1", "pay1")), 1L));

        assertThat(invariants.drift()).as("a projection the writer built").isEmpty();
    }

    // --- P-2 -------------------------------------------------------------------------------------

    @Test
    void a_bumped_month_tier_product_counter_is_counter_drift() {
        execute("""
                update "wqCountMonth" set "cntAll" = "cntAll" + 1
                where "viewKind" = 2 and "pageUnit" = 1 and "productType" = 'bingo'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("counter mismatch").contains("tier=month", "productType=bingo");
    }

    /**
     * Ruling R1: the write path keys every counter at the ALL-types sentinel 0 for now, so a row
     * at a real type id is drift -- the oracle compares "typeId" too, not only the product type.
     */
    @Test
    void a_counter_row_at_a_real_type_id_is_counter_drift() {
        execute("""
                update "wqCountMonth" set "typeId" = 1
                where "viewKind" = 2 and "pageUnit" = 1 and "productType" = 'bingo'""");

        assertThat(invariants.counterDrift()).hasSize(2)
                .anySatisfy(d -> assertThat(d).startsWith("orphan counter row").contains("typeId=1"))
                .anySatisfy(d -> assertThat(d).startsWith("missing counter row").contains("typeId=0"));
    }

    @Test
    void a_stored_zero_counter_row_is_counter_drift() {
        execute("""
                insert into "wqCountDay"
                values (1, 1, 1, 'ghost', 0, date '2034-03-30', 0, 0)""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("orphan counter row").contains("productType=ghost");
    }

    @Test
    void a_deleted_counter_row_is_counter_drift() {
        execute("""
                delete from "wqCountDay"
                where "viewKind" = 2 and "pageUnit" = 0 and "productType" = '' and "typeId" = 0
                  and "period" = date '2034-04-01'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("missing counter row");
    }

    @Test
    void a_changed_excl_cancelled_count_alone_is_counter_drift() {
        // /cc items on April 1: pay1 (cancelled), c1 and pc1 -- excl is 2 of 3.
        execute("""
                update "wqCountDay" set "cntExclCancelled" = "cntExclCancelled" - 1
                where "viewKind" = 2 and "pageUnit" = 0 and "productType" = '' and "typeId" = 0
                  and "period" = date '2034-04-01'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("counter mismatch").contains("expectedAll=3 storedAll=3",
                        "expectedExcl=2 storedExcl=1");
    }

    // --- P-4 -------------------------------------------------------------------------------------

    @Test
    void a_play_without_items_is_aggregation_drift() {
        execute("""
                insert into "wqPlay" ("userId", "batchId", "subBatchId", "startedAt", "endedAt",
                                      "currency")
                values (1, 'b1', 'ghost', timestamptz '2034-03-31 10:00Z',
                        timestamptz '2034-03-31 10:00Z', 'EUR')""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("orphan play row").contains("subBatchId=ghost");
    }

    @Test
    void a_deleted_session_row_is_aggregation_drift() {
        execute("""
                delete from "wqSession" where "batchId" = 'b1'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("missing session row").contains("batchId=b1");
    }

    @Test
    void a_changed_session_play_count_is_aggregation_drift() {
        execute("""
                update "wqSession" set "playCount" = "playCount" + 1""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_changed_session_play_count_excl_cancelled_is_aggregation_drift() {
        // p1's only transaction is cancelled, p2's is not: one surviving play.
        execute("""
                update "wqSession" set "playCountExclCancelled" = "playCountExclCancelled" - 1""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_changed_session_tx_count_excl_cancelled_is_aggregation_drift() {
        execute("""
                update "wqSession" set "txCountExclCancelled" = "txCountExclCancelled" + 1""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_changed_play_net_is_aggregation_drift() {
        execute("""
                update "wqPlay" set "netExclCancelled" = "netExclCancelled" + 1
                where "subBatchId" = 'p2'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("play mismatch").contains("subBatchId=p2");
    }

    @Test
    void a_misplaced_group_locator_is_aggregation_drift() {
        execute("""
                update "wqGroupLocator" set "startedAt" = "startedAt" + interval '1 second'
                where "subBatchId" = ''""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("group locator startedAt mismatch");
    }

    @Test
    void a_session_without_items_is_aggregation_drift() {
        execute("""
                insert into "wqSession" ("userId", "batchId", "startedAt", "endedAt", "currency")
                values (1, 'ghost', timestamptz '2034-03-31 10:00Z', timestamptz '2034-03-31 10:00Z',
                        'EUR')""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("orphan session row").contains("batchId=ghost");
    }

    // --- P-8 -------------------------------------------------------------------------------------

    @Test
    void a_flipped_page_row_visibility_is_high_level_drift() {
        execute("""
                update "wqWebHighLevel" set "visibleExclCancelled" = not "visibleExclCancelled"
                where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("page row visibility mismatch").contains("view=WEB", "refId=b1");
    }

    @Test
    void a_session_row_missing_from_one_view_is_high_level_drift() {
        // Materialisation is per view: the session must have its row in /web as well as /cc.
        execute("""
                delete from "wqWebHighLevel" where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("missing page row").contains("view=WEB", "kind=0", "refId=b1");
    }

    @Test
    void a_session_row_with_another_currency_is_high_level_drift() {
        execute("""
                update "wqCcHighLevel" set "currency" = 'USD' where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("page row currency mismatch").contains("view=CC",
                        "expectedCurrency=EUR", "storedCurrency=USD");
    }

    /** The counter key is the session's product type, so the page row must carry the right one. */
    @Test
    void a_session_row_with_another_product_type_is_high_level_drift() {
        execute("""
                update "wqCcHighLevel" set "productType" = 'lottery' where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("page row key mismatch").contains("view=CC",
                        "expectedProductType=bingo", "storedProductType=lottery");
    }

    @Test
    void a_session_with_another_product_type_is_aggregation_drift() {
        execute("""
                update "wqSession" set "productType" = 'lottery'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_page_row_at_a_stale_key_is_high_level_drift() {
        execute("""
                update "wqCcHighLevel" set "occurredAt" = "occurredAt" + interval '1 day'
                where "kind" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("page row key mismatch").contains("view=CC");
    }

    @Test
    void a_page_row_for_a_cc_only_item_in_web_is_high_level_drift() {
        execute("""
                insert into "wqWebHighLevel"
                values (1, timestamptz '2034-04-01 00:00Z', 4, 'pay1', null, 'EUR', false)""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("orphan page row").contains("view=WEB", "refId=pay1");
    }

    @Test
    void a_deleted_unsessioned_page_row_is_high_level_drift() {
        execute("""
                delete from "wqCcHighLevel" where "kind" = 4""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("missing page row").contains("refId=pay1");
    }

    // --- fixtures --------------------------------------------------------------------------------

    // --- cancellation (spec 6.3) --------------------------------------------------------------

    @Test
    void an_unapplied_cancel_is_cancellation_drift() {
        execute("""
                update "wqWebItem" set "cancelled" = false, "cancelledBy" = null
                where "sourceId" = 't1'""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancel not applied").contains("view=WEB", "sourceId=t1");
    }

    @Test
    void a_wrong_cancelled_by_is_cancellation_drift() {
        execute("""
                update "wqCcItem" set "cancelledBy" = 'zz'
                where "sourceId" = 'pay1'""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancelledBy mismatch").contains("view=CC", "sourceId=pay1");
    }

    @Test
    void a_cancelled_flag_without_any_cancel_is_cancellation_drift() {
        execute("""
                update "wqCcItem" set "cancelled" = true, "cancelledBy" = 'ghost'
                where "sourceId" = 't2'""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("cancelled without a cancel").contains("sourceId=t2");
    }

    @Test
    void a_parked_cancel_whose_target_exists_is_cancellation_drift() {
        execute("""
                insert into "wqPendingCancel" ("source", "cancelSourceId", "cancelsSourceId", "userId",
                    "occurredAt", "type", "amount", "currency")
                values (1, 'c9', 't2', 1, timestamptz '2034-04-01 00:00:00+00', 1, 1, 'EUR')""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("unexpected pending cancel").contains("cancelSourceId=c9");
    }

    private void execute(String sql) {
        assertThat(dsl().execute(sql)).as(sql).isEqualTo(1);
    }

    private static ProjectionItem tx(
            String id, String subBatchId, Instant at, short type, String amount) {
        return new ProjectionItem(1L, SourceKind.TRANSACTION, id, at, "P11", "instants", "b1", subBatchId,
                type, new BigDecimal(amount), "EUR", null, null, null);
    }

    private static ProjectionItem cancel(SourceKind source, String id, String cancels) {
        return new ProjectionItem(1L, source, id, T0.plusSeconds(7_200), "P11", "instants", null, null,
                TxTypes.CANCEL, new BigDecimal("-1.0000"), "EUR", null, cancels, null);
    }
}
