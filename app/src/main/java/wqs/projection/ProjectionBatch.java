package wqs.projection;

import java.util.List;
import wqs.domain.ProjectionItem;

/**
 * One unit of work for the writer. {@code committedOffset} is the stream offset to persist in
 * the same transaction; null means this batch has no offset to advance (for example a replay
 * driven by a loader rather than the stream).
 */
public record ProjectionBatch(String stream, List<ProjectionItem> items, Long committedOffset) {

    public ProjectionBatch {
        items = List.copyOf(items);
    }
}
