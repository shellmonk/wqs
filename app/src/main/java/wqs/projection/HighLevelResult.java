package wqs.projection;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import wqs.domain.ViewKind;

/**
 * Which views one {@link HighLevelUpdater#applyItem} call changed, so per-view HIGH_LEVEL
 * counters can follow.
 *
 * @param inserted            views where a row was actually inserted this call: a new session, a
 *                            session row materialised in a view for the first time, or an
 *                            unsessioned item's own row. Counters increment by one in each view,
 *                            at the CURRENT key (the session's start and productId, or the item's
 *                            occurredAt and productId).
 * @param moved               views where an existing session row changed start and/or productId
 *                            this call. Counters shift those views from the previous key to the
 *                            current one. A product-only change is included, because the counter
 *                            key contains productId although the index primary key does not.
 * @param movedFrom           the session's previous start; when only the product changed this is
 *                            the unchanged start. Null when {@code moved} is empty.
 * @param movedFromProductId  the session's previous productId (may be null); meaningful only
 *                            when {@code moved} is non-empty.
 * @param movedVisible        for each view in {@code moved}, the moved row's
 *                            "visibleExclCancelled" -- whether the row is in the excl-cancelled
 *                            tally, which decides whether the counter move also shifts
 *                            cntExclCancelled. Its key set equals {@code moved}.
 * @param revived             views where an existing session row was hidden (every item of the
 *                            session in that view cancelled) and this live item made it visible
 *                            again. Counters return one row to cntExclCancelled only (cntAll
 *                            already counts it), at the CURRENT key, after any move. Disjoint
 *                            from {@code inserted}.
 */
public record HighLevelResult(
        Set<ViewKind> inserted,
        Set<ViewKind> moved,
        Instant movedFrom,
        Integer movedFromProductId,
        Map<ViewKind, Boolean> movedVisible,
        Set<ViewKind> revived) {

    public static final HighLevelResult NONE =
            new HighLevelResult(Set.of(), Set.of(), null, null, Map.of(), Set.of());

    public HighLevelResult {
        inserted = Set.copyOf(inserted);
        moved = Set.copyOf(moved);
        movedVisible = Map.copyOf(movedVisible);
        revived = Set.copyOf(revived);
        if (revived.stream().anyMatch(inserted::contains)) {
            throw new IllegalArgumentException(
                    "a view cannot be both inserted and revived: inserted=" + inserted
                            + " revived=" + revived);
        }
        if (!movedVisible.keySet().equals(moved)) {
            throw new IllegalArgumentException(
                    "movedVisible must cover exactly the moved views: moved=" + moved
                            + " movedVisible=" + movedVisible);
        }
    }

    /** Number of page-index rows inserted by the call. */
    public int insertedCount() {
        return inserted.size();
    }

    static Set<ViewKind> newViewSet() {
        return EnumSet.noneOf(ViewKind.class);
    }

    static Map<ViewKind, Boolean> newViewMap() {
        return new EnumMap<>(ViewKind.class);
    }
}
