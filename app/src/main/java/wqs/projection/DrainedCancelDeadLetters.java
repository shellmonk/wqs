package wqs.projection;

import wqs.domain.ProjectionItem;

/**
 * Where the writer quarantines a drained cancel whose application failed (spec 6.3, 6.4).
 * Projection-owned so the writer does not depend on the ingest package (spec 8.5 layering);
 * implemented by the dead-letter store.
 *
 * <p>The write must be durable on its own (its own transaction): it happens while the projection
 * transaction is still open. Should that transaction later roll back, the cancel is restored to
 * "wqPendingCancel", drained and fails again on retry, and is recorded again -- a duplicate
 * dead-letter row, never a lost one.
 */
public interface DrainedCancelDeadLetters {

    /** The cancel's own stream offset is not retained while it is parked, so none is recorded. */
    void recordFailedDrainedCancel(String stream, ProjectionItem cancel, Exception error);
}
