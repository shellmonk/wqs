package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A decoded {@code walAccountTransactions} message. Only the fields WQS stores are kept; the rest of
 * the wire message is dropped. Empty strings on the wire are {@code null} here.
 *
 * <p>{@code ownerId} is {@code userContext.ownerId} ({@code "{type}-{id}"}) in its canonical spelling.
 * The names are the columns', not the wire's: {@code transactionSubtype} is {@code subtype},
 * {@code accountId} is {@code account}, and nested fields lose their parent
 * ({@code accountContext.accountType} is {@code accountType}).
 */
public record StreamMessage(
        String id,
        String ownerId,
        Instant created,
        String accountType,
        String accountNumber,
        String accountId,
        String batchId,
        String subBatchId,
        Boolean batchEnded,
        String transactionType,
        String transactionSubtype,
        String productId,
        String productType,
        String currency,
        BigDecimal amount,
        BigDecimal totalPostBalance,
        BigDecimal reservedAmount,
        BigDecimal nonWithdrawableAmount,
        boolean monetary,
        String description,
        String fromDescription,
        String toDescription,
        Long paymentId,
        Long claimId,
        String extTrxId,
        String extSystemId,
        String transactionReference,
        String channel,
        Instant issued) {}
