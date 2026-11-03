package wqs.projection;

import wqs.domain.SourceKind;

/**
 * A transaction's identity across sources: the "wqPrjTransactionLocator" key (source, sourceId), plus the owning
 * userId. sourceId alone is not unique -- different sources may reuse the same id string -- and a
 * cancel only ever resolves an original of the same user, so a parked cancel waits for all three.
 */
public record TransactionRef(long userId, SourceKind source, String sourceId) {

    public static TransactionRef of(wqs.domain.ProjectionTransaction transaction) {
        return new TransactionRef(transaction.userId(), transaction.source(), transaction.sourceId());
    }
}
