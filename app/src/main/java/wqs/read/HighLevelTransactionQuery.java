package wqs.read;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The transaction-table reads of level-1/2 assembly for one view (spec 7.3; one class per view, spec 2.2
 * rule 4): a page's session children and its unsessioned transactions, each in ONE query bounded by the
 * page -- every probe is per session (its batch id, through the "_batch" index) or per key (its
 * exact "occurredAt"), never a scan of the time span the page covers (final review F1). Each row
 * carries its type code as stored (plan D1). Stateless: every method takes the reader transaction's client.
 */
public interface HighLevelTransactionQuery {

    ViewKind view();

    /** What this query's endpoint shows (plan D1): every statement binds it. */
    ReadPolicy policy();

    /**
     * The view's rows of the given sessions (by ("userId", "batchId")), newest first ("occurredAt" desc, "transactionId" desc, as level
     * 0): each session's transactions in its own {@code ["startedAt", "endedAt"]}, plus its cancellation
     * rows dated after "endedAt" and before {@code to} (cancels never roll up, so a late cancel
     * leaves "endedAt" alone; final review F3). Only types whose code is in {@code typeCodes} when
     * it is non-empty; only non-cancelled transactions when {@code excludeCancelled} (level 0's
     * predicate, so cancellation rows stay).
     */
    List<TransactionEntry> sessionTransactions(JdbcClient tx, long userId, Set<String> batchIds,
                                 Instant to, Set<String> typeCodes, boolean excludeCancelled);

    /** The view's transactions for standalone entry keys ("kind" = source code, "refId" = sourceId). */
    Map<HighLevelKey, TransactionEntry> unsessioned(JdbcClient tx, long userId, List<HighLevelKey> keys);
}
