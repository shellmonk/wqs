package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** A row to store in {@code "wqPrjTransaction"}, whichever feed it came from. */
public record NewTransaction(
        short ownerType,
        long userId,
        Instant occurredAt,
        Source source,
        String sourceId,
        String type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        String productId,
        String productType,
        String batchId,
        String subBatchId,
        String transactionReference,
        Map<String, String> detail) {

    public NewTransaction {
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
