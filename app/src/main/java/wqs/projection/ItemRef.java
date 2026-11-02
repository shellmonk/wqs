package wqs.projection;

import wqs.domain.SourceKind;

/**
 * An item's identity across sources: the "wqItemLocator" key (source, sourceId), plus the owning
 * userId. sourceId alone is not unique -- different sources may reuse the same id string -- and a
 * cancel only ever resolves an original of the same user, so a parked cancel waits for all three.
 */
public record ItemRef(long userId, SourceKind source, String sourceId) {

    public static ItemRef of(wqs.domain.ProjectionItem item) {
        return new ItemRef(item.userId(), item.source(), item.sourceId());
    }
}
