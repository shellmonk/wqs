package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

/** drainFor's one-statement unnest form, with several targets in one call. */
class PendingCancelStoreTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-05-10T10:00:00Z");

    @BeforeEach
    void setUp() {
        clearProjection();
    }

    @Test
    void drain_for_several_targets_returns_exactly_their_cancels_in_order() {
        // Same cancelled sourceId under two sources and two users: only the exact triple matches.
        ProjectionTransaction txOfUser1 = cancel(1L, SourceKind.TRANSACTION, "c-tx-1", "x1", "P11", "instants",
                "b1", "p1", "-5.1234", new BigDecimal("94.8766"), "{\"k\":1}");
        ProjectionTransaction secondTxOfUser1 = cancel(1L, SourceKind.TRANSACTION, "c-tx-0", "x1", null, null,
                null, null, "-0.0001", null, null);
        ProjectionTransaction paymentOfUser1 = cancel(1L, SourceKind.PAYMENT, "c-pay-1", "x1", null, null,
                null, null, "-2.0000", null, null);
        ProjectionTransaction prizeOfUser1 = cancel(1L, SourceKind.CLAIMABLE_PRIZE, "c-pr-1", "x2", "P12", "lotto",
                null, null, "-3.5000", null, null);
        ProjectionTransaction txOfUser2 = cancel(2L, SourceKind.TRANSACTION, "c-tx-2", "x1", null, null,
                null, null, "-1.0000", null, null);
        inTx().executeWithoutResult(status -> {
            PendingCancelStore store = new PendingCancelStore(jdbc());
            List.of(txOfUser1, secondTxOfUser1, paymentOfUser1, prizeOfUser1, txOfUser2).forEach(store::park);
        });

        List<ProjectionTransaction> drained = inTx().execute(status -> new PendingCancelStore(jdbc()).drainFor(Set.of(
                new TransactionRef(1L, SourceKind.TRANSACTION, "x1"),
                new TransactionRef(1L, SourceKind.CLAIMABLE_PRIZE, "x2"),
                new TransactionRef(1L, SourceKind.TRANSACTION, "absent"))));

        // Ordered by (source, cancel sourceId); every field as it was parked, scale and nulls included.
        assertThat(drained).containsExactly(secondTxOfUser1, txOfUser1, prizeOfUser1);
        assertThat(drained.get(1).amount().toPlainString()).isEqualTo("-5.1234");
        assertThat(drained.get(1).balanceAfter().toPlainString()).isEqualTo("94.8766");
        assertThat(db().column("select \"cancelSourceId\" from \"wqPrjPendingCancel\" order by 1", String.class))
                .as("the other source's and the other user's cancels stay parked")
                .containsExactly("c-pay-1", "c-tx-2");
    }

    private static ProjectionTransaction cancel(long userId, SourceKind source, String sourceId, String cancels,
                                         String productId, String productType, String batchId, String subBatchId,
                                         String amount, BigDecimal balanceAfter, String detail) {
        return new ProjectionTransaction(userId, source, sourceId, T0, productId, productType, batchId, subBatchId,
                TxTypes.CANCEL, new BigDecimal(amount), "EUR", balanceAfter, cancels, detail);
    }
}
