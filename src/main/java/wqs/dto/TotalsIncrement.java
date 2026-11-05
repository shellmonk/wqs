package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one row adds to its session's and play's totals: one to the row count, and its absolute
 * amount to the played or won total according to its type's {@link MoneyDirection}.
 */
public record TotalsIncrement(
        String ownerId,
        String batchId,
        String subBatchId,
        Instant created,
        String productId,
        String productType,
        String currency,
        BigDecimal plays,
        BigDecimal wins) {

    public BigDecimal net() {
        return wins.subtract(plays);
    }

    public static TotalsIncrement of(IncomingTransaction t) {
        MoneyDirection direction = TransactionType.directionOf(t.transactionType());
        BigDecimal amount = t.amount().abs();
        return new TotalsIncrement(t.ownerId(), t.batchId(), t.subBatchId(), t.created(),
                t.productId(), t.productType(), t.currency(),
                direction == MoneyDirection.PLAY ? amount : BigDecimal.ZERO,
                direction == MoneyDirection.WIN ? amount : BigDecimal.ZERO);
    }
}
