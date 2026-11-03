package wqs.read;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;

/** One level-0 transaction as returned to clients. "transactionId" and "ingestedAt" are deliberately internal. */
public record TransactionEntry(
        String kind,
        String sourceId,
        Instant occurredAt,
        String productId,
        String productType,
        String batchId,
        String subBatchId,
        String transactionType,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        boolean cancelled,
        String cancelledBy,
        String cancels,
        @JsonRawValue String detail) {}
