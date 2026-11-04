package wqs.projection;

import wqs.domain.ProjectionTransaction;

/**
 * Where the writer quarantines a single transaction an ingest rule rejects, while the rest of its batch
 * applies (spec 4.1, plan D6/D7). Projection-owned so the writer does not depend on the ingest
 * package (spec 8.5 layering); implemented by the dead-letter store.
 *
 * <p>As with {@link DrainedCancelDeadLetters}, the write is durable on its own (its own
 * transaction) while the projection transaction is still open. Should that transaction roll back,
 * the retry rejects the projected transaction again and records it again -- a duplicate dead-letter row, never a
 * lost one.
 */
public interface RejectedTransactionDeadLetters {

    /** Rejection reasons, recorded verbatim as the dead letter's error text. */
    String CURRENCY_MISMATCH = "currency-mismatch";
    String EINSTANT_UNSESSIONED = "einstant-unsessioned";
    String IMMUTABLE_FIELD_CHANGED = "immutable-field-changed";
    /** A batched row of a source the producer never batches ({@link wqs.domain.SourceKind#carriesBatch}). */
    String SESSIONED_SOURCE_NOT_ON_EVERY_VIEW = "sessioned-source-not-on-every-view";

    /**
     * @param offset the transaction's own stream offset, or null when unknown
     * @param reason one of the constants above
     */
    void recordRejected(String stream, Long offset, ProjectionTransaction transaction, String reason);
}
