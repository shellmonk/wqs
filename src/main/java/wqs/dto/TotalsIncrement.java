package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one row adds to its session's and play's totals: one to the row count, and its absolute
 * amount to the played or won total according to its type's {@link MoneyDirection}.
 */
public record TotalsIncrement(
        short ownerType,
        long userId,
        String batchId,
        String subBatchId,
        Instant occurredAt,
        String productId,
        String productType,
        String currency,
        BigDecimal plays,
        BigDecimal wins) {

    public BigDecimal net() {
        return wins.subtract(plays);
    }

    public static TotalsIncrement of(NewTransaction t) {
        MoneyDirection direction = TransactionType.directionOf(t.type());
        BigDecimal amount = t.amount().abs();
        return new TotalsIncrement(t.ownerType(), t.userId(), t.batchId(), t.subBatchId(), t.occurredAt(),
                t.productId(), t.productType(), t.currency(),
                direction == MoneyDirection.PLAY ? amount : BigDecimal.ZERO,
                direction == MoneyDirection.WIN ? amount : BigDecimal.ZERO);
    }
}
