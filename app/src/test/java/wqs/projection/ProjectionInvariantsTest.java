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
                // A claimable prize a day earlier at a lower product: the session's key moves.
                new ProjectionTransaction(1L, SourceKind.CLAIMABLE_PRIZE, "a1",
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
    void a_bumped_product_count_is_counter_drift() {
        execute("""
                update "wqOwnerMonth" set "cnt" = "cnt" + 1
                where "unit" = 1 and "productType" = 'bingo'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("count mismatch").contains("unit=ENTRY", "productType=bingo");
    }

    /**
     * Counts are exact combinations: a TX row at another type code is drift -- the oracle compares
     * "type" too, not only the product type. (An ENTRY row cannot carry a type at all: a CHECK.)
     */
    @Test
    void a_count_row_at_another_type_code_is_counter_drift() {
        execute("""
                update "wqOwnerMonth" set "type" = 'TT_ELSEWHERE'
                where "unit" = 0 and "source" = 4 and "type" <> '%s'""".formatted(TxTypes.CANCEL));

        assertThat(invariants.counterDrift()).hasSize(2)
                .anySatisfy(d -> assertThat(d).startsWith("orphan count row").contains(" type=TT_ELSEWHERE "))
                .anySatisfy(d -> assertThat(d).startsWith("missing count row").contains(" source=4 "));
    }

    @Test
    void a_stored_zero_count_row_is_counter_drift() {
        execute("""
                insert into "wqOwnerMonth" ("ownerType", "userId", "month", "source", "unit", "productType", "type",
                    "zeroAmount", "cnt", "cntExclCancelled")
                values (1, 1, date '2034-03-01', 1, 1, 'ghost', '', false, 0, 0)""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("orphan count row").contains("productType=ghost");
    }

    @Test
    void a_deleted_count_row_is_counter_drift() {
        execute("""
                delete from "wqOwnerMonth"
                where "unit" = 1 and "source" = 4 and "month" = date '2034-04-01'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("missing count row").contains("source=4", "unit=ENTRY");
    }

    @Test
    void a_changed_excl_cancelled_count_alone_is_counter_drift() {
        // pay1 is cancelled: its ENTRY counts 1, 0 of it visible.
        execute("""
                update "wqOwnerMonth" set "cntExclCancelled" = "cntExclCancelled" + 1
                where "unit" = 1 and "source" = 4 and "month" = date '2034-04-01'""");

        assertThat(invariants.counterDrift()).singleElement().asString()
                .startsWith("count mismatch").contains("expectedCnt=1 storedCnt=1",
                        "expectedExcl=0 storedExcl=1");
    }

    /** A standalone entry belongs to its own source: counted under another, it is drift on both keys. */
    @Test
    void an_entry_counted_under_another_source_is_counter_drift() {
        execute("""
                update "wqOwnerMonth" set "source" = 1 where "unit" = 1 and "source" = 4""");

        assertThat(invariants.counterDrift()).hasSize(2)
                .anySatisfy(d -> assertThat(d).startsWith("orphan count row").contains(" source=1 "))
                .anySatisfy(d -> assertThat(d).startsWith("missing count row").contains(" source=4 "));
    }

    // --- P-4 -------------------------------------------------------------------------------------

    @Test
    void a_play_without_transactions_is_aggregation_drift() {
        execute("""
                insert into "wqPrjPlay" ("ownerType", "userId", "batchId", "subBatchId", "startedAt", "endedAt",
                                      "currency")
                values (1, 1, 'b1', 'ghost', timestamptz '2034-03-31 10:00Z',
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
    void a_session_start_not_at_its_earliest_transaction_is_aggregation_drift() {
        execute("""
                update "wqPrjSession" set "startedAt" = "startedAt" - interval '1 second'""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b1");
    }

    @Test
    void a_session_without_transactions_is_aggregation_drift() {
        execute("""
                insert into "wqPrjSession" ("ownerType", "userId", "batchId", "startedAt", "endedAt", "currency")
                values (1, 1, 'ghost', timestamptz '2034-03-31 10:00Z', timestamptz '2034-03-31 10:00Z',
                        'EUR')""");

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("orphan session row").contains("batchId=ghost");
    }

    // --- P-8 -------------------------------------------------------------------------------------

    /** "visibleExclCancelled" is generated from the survivor count: corrupting the count flips it. */
    @Test
    void a_flipped_session_visibility_is_high_level_drift() {
        execute("""
                update "wqPrjSession" set "txCountExclCancelled" = 0""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("session entry mismatch").contains("batchId=b1", "expectedVisible=true", "storedVisible=false");
    }

    /** The first source decides which policies show the session, and keys its count. */
    @Test
    void a_session_with_another_first_source_is_high_level_drift() {
        execute("""
                update "wqPrjSession" set "firstSource" = 4""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("session entry mismatch").contains("expectedFirstSource=3", "storedFirstSource=4");
    }

    @Test
    void a_session_with_another_first_source_id_is_high_level_drift() {
        execute("""
                update "wqPrjSession" set "firstSourceId" = 'zz'""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("session entry mismatch").contains("expectedFirstSourceId=a1", "storedFirstSourceId=zz");
    }

    /** The count key is the session's product type, so the session must carry the right one. */
    @Test
    void a_session_with_another_product_type_is_high_level_drift() {
        execute("""
                update "wqPrjSession" set "productType" = 'lottery'""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("session entry mismatch").contains("expectedProductType=bingo", "storedProductType=lottery");
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
     * no transaction's product -- while the first transaction's is (P13, arcade); a session carrying
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

        assertThat(invariants.aggregationDrift()).singleElement().asString()
                .startsWith("session mismatch").contains("batchId=b2");
        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("session entry mismatch").contains("batchId=b2", "expectedProductId=P13", "storedProductId=P03");
    }

    private static ProjectionTransaction labelled(String id, Instant at, String productId, String productType) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, at, productId, productType, "b2", "b2-p",
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }

    @Test
    void a_session_at_a_stale_start_is_high_level_drift() {
        execute("""
                update "wqPrjSession" set "startedAt" = "startedAt" + interval '1 day'""");

        assertThat(invariants.highLevelDrift()).singleElement().asString()
                .startsWith("session entry mismatch").contains("batchId=b1");
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

    /** A zero-amount cancel is a transaction row like any other, and counts as t2's cancel. */
    @Test
    void a_zero_amount_cancel_row_is_seen_by_the_cancellation_oracle() {
        execute("""
                insert into "wqPrjTransaction" ("ownerType", "userId", "transactionId", "source", "sourceId",
                    "occurredAt", "type", "amount", "currency", "cancels")
                values (1, 1, -1, 1, 'cz', timestamptz '2034-04-01 02:00:00+00', 'TT_CANCEL', 0, 'EUR', 't2')""");

        assertThat(invariants.cancellationDrift()).asString().contains("cancel not applied").contains("sourceId=t2");
    }

    /** Owners are (ownerType, userId): a cancel row of owner 2-1 is not a cancel of player 1-1's t2. */
    @Test
    void a_cancel_of_another_owner_type_is_not_a_cancel_for_the_cancellation_oracle() {
        execute("""
                update "wqPrjTransaction" set "cancelled" = true, "cancelledBy" = 'cx' where "sourceId" = 't2'""");
        execute("""
                insert into "wqPrjTransaction" ("ownerType", "userId", "transactionId", "source", "sourceId",
                    "occurredAt", "type", "amount", "currency", "cancels")
                values (2, 1, -2, 1, 'cx', timestamptz '2034-04-01 02:00:00+00', 'TT_CANCEL', 1, 'EUR', 't2')""");

        assertThat(invariants.cancellationDrift()).asString().contains("cancelled without a cancel")
                .contains("sourceId=t2");
    }

    @Test
    void a_parked_cancel_whose_target_exists_is_cancellation_drift() {
        execute("""
                insert into "wqPrjPendingCancel" ("source", "cancelSourceId", "targetSourceId", "ownerType", "userId",
                    "occurredAt", "type", "amount", "currency")
                values (1, 'c9', 't2', 1, 1, timestamptz '2034-04-01 00:00:00+00', 'TT_CANCEL', 1, 'EUR')""");

        assertThat(invariants.cancellationDrift()).singleElement().asString()
                .startsWith("unexpected pending cancel").contains("cancelSourceId=c9");
    }

    // --- resolution (plan D3) -----------------------------------------------------------------

    /**
     * A cancel parked longer than the threshold may be removed by pendingCancelResolution: then its
     * absent target, and a target arriving after the resolution, are legitimately uncancelled. The same
     * states with a young cancel, or a target that arrived in time, are drift.
     */
    @Test
    void a_resolved_cancel_leaves_its_target_legitimately_uncancelled() {
        execute("""
                insert into "wqPrjTransaction" ("ownerType", "userId", "transactionId", "source", "sourceId",
                    "occurredAt", "type", "amount", "currency", "cancels", "ingestedAt")
                values (1, 1, -3, 1, 'c-exp', timestamptz '2034-04-01 03:00:00+00', 'TT_CANCEL', 1, 'EUR', 't-late',
                        now() - interval '8 days')""");
        assertThat(invariants.cancellationDrift()).as("resolved: no parked row, target absent").isEmpty();

        execute("""
                insert into "wqPrjTransaction" ("ownerType", "userId", "transactionId", "source", "sourceId",
                    "occurredAt", "type", "amount", "currency")
                values (1, 1, -4, 1, 't-late', timestamptz '2034-04-01 01:00:00+00', 'TT_BUY', 1, 'EUR')""");
        assertThat(invariants.cancellationDrift()).as("resolved, then the target arrived").isEmpty();

        execute("""
                update "wqPrjTransaction" set "ingestedAt" = now() - interval '7 days 23 hours'
                where "sourceId" = 't-late'""");
        assertThat(invariants.cancellationDrift()).as("the target arrived before the resolution could run")
                .singleElement().asString().startsWith("cancel not applied").contains("sourceId=t-late");

        execute("""
                delete from "wqPrjTransaction" where "sourceId" = 't-late'""");
        execute("""
                update "wqPrjTransaction" set "ingestedAt" = now() - interval '6 days' where "sourceId" = 'c-exp'""");
        assertThat(invariants.cancellationDrift()).as("too young to be resolved")
                .singleElement().asString().startsWith("missing pending cancel").contains("cancelSourceId=c-exp");
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
