package wqs.projection;

import wqs.domain.SourceKind;

/**
 * A transaction's identity across sources: (source, sourceId), the leading columns of "wqPrjTransaction"'s unique key, plus the owner
 * (ownerType, userId). sourceId alone is not unique -- different sources may reuse the same id string -- and a
 * cancel only ever resolves an original of the same owner, so a parked cancel waits for all four.
 */
public record TransactionRef(short ownerType, long userId, SourceKind source, String sourceId) {

    /** A player's transaction (owner type 1). */
    public TransactionRef(long userId, SourceKind source, String sourceId) {
        this(wqs.domain.ProjectionTransaction.PLAYER, userId, source, sourceId);
    }

    public static TransactionRef of(wqs.domain.ProjectionTransaction transaction) {
        return new TransactionRef(transaction.ownerType(), transaction.userId(), transaction.source(),
                transaction.sourceId());
    }
}
