package wqs.projection;

import java.util.Collections;
import java.util.List;
import wqs.domain.ProjectionTransaction;

/**
 * One unit of work for the writer. {@code committedOffset} is the stream offset to persist in
 * the same transaction; null means this batch has no offset to advance (for example a replay
 * driven by a loader rather than the stream).
 *
 * <p>{@code transactionOffsets}, when known, holds each transaction's OWN stream offset, index-aligned with
 * {@code transactions}, so a dead-lettered transaction records where it really was rather than the batch's
 * highest offset. Null when unknown (a loader, a test).
 */
public record ProjectionBatch(String stream, List<ProjectionTransaction> transactions, Long committedOffset, List<Long> transactionOffsets) {

    public ProjectionBatch {
        transactions = List.copyOf(transactions);
        if (transactionOffsets != null) {
            if (transactionOffsets.size() != transactions.size()) {
                throw new IllegalArgumentException("transactionOffsets must align with transactions");
            }
            transactionOffsets = Collections.unmodifiableList(new java.util.ArrayList<>(transactionOffsets));
        }
    }

    public ProjectionBatch(String stream, List<ProjectionTransaction> transactions, Long committedOffset) {
        this(stream, transactions, committedOffset, null);
    }

    /** The i-th transaction's own offset, or null when not known. */
    public Long offsetOf(int index) {
        return transactionOffsets == null ? null : transactionOffsets.get(index);
    }
}
