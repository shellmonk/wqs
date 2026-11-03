package wqs.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A normalized, source-agnostic transaction, ready to be written once to "wqPrjTransaction" (each view sees it through its own SQL view).
 *
 * Unenumerated source-specific fields live in {@code detailJson} until O-13 is answered
 * (spec section 13). {@code detailJson} is the raw JSON text, so that the domain package carries no
 * persistence-library dependency.
 *
 * {@code cancels} is the sourceId this transaction cancels, or null; blank is normalised to null like
 * the batch ids, so "not a cancellation" has exactly one representation.
 *
 * A blank {@code batchId} or {@code subBatchId} is normalised to null here, at the domain
 * boundary: "no session" and "no play" have exactly one representation.
 *
 * {@code productId} and {@code productType} are the wire strings (spec 4.1), normalised the same
 * way. The empty string is the counters' ALL-product-types sentinel, so it too must be
 * unreachable from input. {@code type} is the transaction-type code as it arrived (plan D1;
 * {@link TransactionType} gives its direction, NEUTRAL when unknown), never blank -- the empty
 * string is the counters' ALL-transaction-types sentinel.
 */
public record ProjectionTransaction(
        long userId,
        SourceKind source,
        String sourceId,
        Instant occurredAt,
        String productId,
        String productType,
        String batchId,
        String subBatchId,
        String type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        String cancels,
        String detailJson) {

    public ProjectionTransaction {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required -- it is the idempotency key");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required -- it is the partition key");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "a transaction type code is required (blank is the counters' ALL sentinel)");
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

    /** True if the transaction belongs to a play (a sub-batch) within its session. */
    public boolean hasPlay() {
        return subBatchId != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
