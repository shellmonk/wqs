package wqs.projection.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Wire shape of a stream message, limited to the fields known today. The full list is open
 * (O-13); everything not yet enumerated arrives in {@code extra} and is projected into
 * "detail" jsonb, to be promoted to real columns only if it needs indexing or filtering.
 */
public record StreamPayload(
        String transactionId,
        long userId,
        Instant occurredAt,
        Integer productId,
        String batchId,
        String subBatchId,
        short type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        String cancelsTransactionId,
        Map<String, String> extra) {

    public StreamPayload {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
