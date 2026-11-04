package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * A decoded {@code walAccountTransactions} message. Only the fields WQS uses are kept; the rest of
 * the wire message is dropped. Empty strings on the wire are {@code null} here.
 *
 * <p>{@code ownerId} is {@code userContext.ownerId} ({@code "{type}-{id}"}) in its canonical spelling.
 * {@code detail} holds only allowlisted fields, under dotted keys such as
 * {@code accountContext.accountType}.
 */
public record StreamMessage(
        String id,
        String ownerId,
        Instant created,
        String type,
        BigDecimal amount,
        String currency,
        BigDecimal totalPostBalance,
        String productId,
        String productType,
        String batchId,
        String subBatchId,
        String transactionReference,
        Map<String, String> detail) {

    public StreamMessage {
        detail = Map.copyOf(detail);
    }
}
