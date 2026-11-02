package wqs.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A normalized, source-agnostic item, ready to be written to one or more view tables.
 *
 * Unenumerated source-specific fields live in {@code detailJson} until O-13 is answered
 * (spec section 13). {@code detailJson} is a String rather than jOOQ's JSONB so that the
 * domain package carries no jOOQ dependency.
 *
 * {@code cancels} is the sourceId this item cancels, or null. Phase 3 acts on it.
 *
 * A blank {@code batchId} or {@code subBatchId} is normalised to null here, at the domain
 * boundary: "no session" and "no play" have exactly one representation, and the empty string --
 * which "wqGroupLocator" reserves as the session marker -- is unreachable from input.
 */
public record ProjectionItem(
        long userId,
        SourceKind source,
        String sourceId,
        Instant occurredAt,
        Integer productId,
        String batchId,
        String subBatchId,
        short type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        String cancels,
        String detailJson) {

    public ProjectionItem {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required -- it is the idempotency key");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required -- it is the partition key");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        batchId = blankToNull(batchId);
        subBatchId = blankToNull(subBatchId);
    }

    public boolean isCancellation() {
        return cancels != null && !cancels.isBlank();
    }

    public boolean isSessioned() {
        return batchId != null;
    }

    /** True if the item belongs to a play (a sub-batch) within its session. */
    public boolean hasPlay() {
        return subBatchId != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
