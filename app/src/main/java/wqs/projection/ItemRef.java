package wqs.projection;

import wqs.domain.SourceKind;

/**
 * An item's identity across sources: the "wqItemLocator" key. sourceId alone is not unique --
 * different sources may reuse the same id string.
 */
public record ItemRef(SourceKind source, String sourceId) {

    public static ItemRef of(wqs.domain.ProjectionItem item) {
        return new ItemRef(item.source(), item.sourceId());
    }
}
