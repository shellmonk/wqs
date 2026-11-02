package wqs.read;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import wqs.domain.ViewKind;

/**
 * The item-table reads of level-1/2 assembly for one view (spec 7.3; one class per view, spec 2.2
 * rule 4): a page's session children and its unsessioned items, each in ONE query bounded by the
 * page. Stateless: every method takes the reader transaction's DSL.
 */
public interface HighLevelItemQuery {

    ViewKind view();

    /**
     * The view's items of the given sessions with "occurredAt" in {@code [lo, hi]}, newest first
     * ("occurredAt" desc, "itemId" desc, as level 0). Only types in {@code typeIds} when it is
     * non-empty; only non-cancelled items when {@code excludeCancelled} (level 0's predicate, so
     * cancellation rows stay). The caller narrows each session to its own window.
     */
    List<ItemEntry> sessionItems(DSLContext tx, long userId, Set<String> batchIds, Instant lo, Instant hi,
                                 Set<Short> typeIds, boolean excludeCancelled);

    /** The view's items for unsessioned page-index keys ("kind" = source code, "refId" = sourceId). */
    Map<HighLevelKey, ItemEntry> unsessioned(DSLContext tx, long userId, List<HighLevelKey> keys);
}
