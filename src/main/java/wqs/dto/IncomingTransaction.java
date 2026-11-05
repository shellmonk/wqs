package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** A transaction arriving from a feed, before it is stored in {@code "wqPrjTransactions"}; either feed maps to it. */
public record IncomingTransaction(
        String ownerId,
        Instant created,
        Source source,
        String sourceId,
        String transactionType,
        BigDecimal amount,
        String currency,
        BigDecimal totalPostBalance,
        String productId,
        String productType,
        String batchId,
        String subBatchId,
        String transactionReference,
        Map<String, String> detail) {

    public IncomingTransaction {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /**
     * A zero-amount stream transaction: stored, never shown, and adds nothing to session or play
     * totals. Mirrors the generated column {@code "zeroAmount"}.
     */
    public boolean zeroAmount() {
        return source == Source.TRANSACTION && amount.signum() == 0;
    }
}
