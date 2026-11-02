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
 * {@code cancels} is the sourceId this item cancels, or null; blank is normalised to null like
 * the batch ids, so "not a cancellation" has exactly one representation.
 *
 * A blank {@code batchId} or {@code subBatchId} is normalised to null here, at the domain
 * boundary: "no session" and "no play" have exactly one representation, and the empty string --
 * which "wqGroupLocator" reserves as the session marker -- is unreachable from input.
 *
 * {@code productId} and {@code productType} are the wire strings (spec 4.1), normalised the same
 * way. The empty string is the counters' ALL-product-types sentinel, so it too must be
 * unreachable from input. {@code type} is a "wqTxType" id (plan D2), never 0 -- the
 * ALL-transaction-types sentinel.
 */
public record ProjectionItem(
        long userId,
        SourceKind source,
        String sourceId,
        Instant occurredAt,
        String productId,
        String productType,
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
        if (type < 1) {
            throw new IllegalArgumentException(
                    "type " + type + " is not a transaction-type id (ids start at 1; 0 is ALL)");
        }
        productId = blankToNull(productId);
        productType = blankToNull(productType);
        batchId = blankToNull(batchId);
        subBatchId = blankToNull(subBatchId);
        cancels = blankToNull(cancels);
    }

    public boolean isCancellation() {
        return cancels != null;
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
