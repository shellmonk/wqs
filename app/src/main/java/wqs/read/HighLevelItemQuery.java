package wqs.read;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.domain.ViewKind;

/**
 * The item-table reads of level-1/2 assembly for one view (spec 7.3; one class per view, spec 2.2
 * rule 4): a page's session children and its unsessioned items, each in ONE query bounded by the
 * page -- every probe is per session (its batch id, through the "_batch" index) or per key (its
 * exact "occurredAt"), never a scan of the time span the page covers (final review F1). Each row
 * carries its type code from "wqTxType" in the same statement, so no registry lookup is needed
 * whatever the cache holds. Stateless: every method takes the reader transaction's client.
 */
public interface HighLevelItemQuery {

    ViewKind view();

    /**
     * The view's rows of the given sessions (those of {@code batchIds} with "startedAt" in
     * {@code [startedFrom, startedTo]}), newest first ("occurredAt" desc, "itemId" desc, as level
     * 0): each session's items in its own {@code ["startedAt", "endedAt"]}, plus its cancellation
     * rows dated after "endedAt" and before {@code to} (cancels never roll up, so a late cancel
     * leaves "endedAt" alone; final review F3). Only types whose code is in {@code typeCodes} when
     * it is non-empty; only non-cancelled items when {@code excludeCancelled} (level 0's
     * predicate, so cancellation rows stay).
     */
    List<ItemEntry> sessionItems(JdbcClient tx, long userId, Set<String> batchIds, Instant startedFrom,
                                 Instant startedTo, Instant to, Set<String> typeCodes, boolean excludeCancelled);

    /** The view's items for unsessioned page-index keys ("kind" = source code, "refId" = sourceId). */
    Map<HighLevelKey, ItemEntry> unsessioned(JdbcClient tx, long userId, List<HighLevelKey> keys);
}
