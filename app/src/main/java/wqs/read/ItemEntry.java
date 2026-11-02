package wqs.read;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;

/** One level-0 item as returned to clients. "itemId" and "ingestedAt" are deliberately internal. */
public record ItemEntry(
        String kind,
        String sourceId,
        Instant occurredAt,
        String productId,
        String batchId,
        String subBatchId,
        short type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        boolean cancelled,
        String cancelledBy,
        String cancels,
        @JsonRawValue String detail) {}
