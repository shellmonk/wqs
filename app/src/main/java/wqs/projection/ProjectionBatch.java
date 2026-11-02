package wqs.projection;

import java.util.Collections;
import java.util.List;
import wqs.domain.ProjectionItem;

/**
 * One unit of work for the writer. {@code committedOffset} is the stream offset to persist in
 * the same transaction; null means this batch has no offset to advance (for example a replay
 * driven by a loader rather than the stream).
 *
 * <p>{@code itemOffsets}, when known, holds each item's OWN stream offset, index-aligned with
 * {@code items}, so a dead-lettered item records where it really was rather than the batch's
 * highest offset. Null when unknown (a loader, a test).
 */
public record ProjectionBatch(String stream, List<ProjectionItem> items, Long committedOffset, List<Long> itemOffsets) {

    public ProjectionBatch {
        items = List.copyOf(items);
        if (itemOffsets != null) {
            if (itemOffsets.size() != items.size()) {
                throw new IllegalArgumentException("itemOffsets must align with items");
            }
            itemOffsets = Collections.unmodifiableList(new java.util.ArrayList<>(itemOffsets));
        }
    }

    public ProjectionBatch(String stream, List<ProjectionItem> items, Long committedOffset) {
        this(stream, items, committedOffset, null);
    }

    /** The i-th item's own offset, or null when not known. */
    public Long offsetOf(int index) {
        return itemOffsets == null ? null : itemOffsets.get(index);
    }
}
