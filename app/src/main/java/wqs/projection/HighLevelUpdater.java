package wqs.projection;

import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionTransaction;
import wqs.domain.ViewKind;

/**
 * Maintains the level-1/2 page index (spec 5.6): exactly one row per session and one per
 * unsessioned transaction.
 *
 * A session row is keyed and populated from the SESSION's current key in {@link RollupResult}
 * (its start and product), never from the triggering projected transaction: a session's start moves earlier when
 * an earlier projected transaction arrives, so the row is MOVED (the start is in the primary key and the partition
 * key) and its productId / productType updated in place when only the product changed. A transaction joining a
 * session with an unchanged key inserts nothing in views that already hold the row, which keeps
 * page counts equal to the number of high-level rows rather than the number of transactions.
 * Every sessioned projected transaction also ensures the row exists in each view its own source feeds, so the end
 * state does not depend on which source opened the session.
 *
 * Visibility ("visibleExclCancelled") is per view: a session row in view V is visible while the
 * session has at least one surviving (non-cancelled, non-cancellation) transaction whose source feeds V.
 * {@link CancellationApplier} hides a row when a cancel removes the last such transaction; here, a live
 * transaction joining a session whose row in a view its source feeds is hidden REVIVES it, since the
 * transaction is itself a surviving transaction in that view.
 *
 * Currency: a session row carries the SESSION's currency (from {@link RollupResult#sessionCurrency},
 * exactly as "wqPrjSession" holds it). A session is single-currency -- a transaction of another currency
 * is dead-lettered before it gets here (plan D6) -- so the currency never changes and rows never
 * need re-syncing. An unsessioned row carries its transaction's own currency.
 *
 * A cancellation never creates a row; its effects are applied by {@link CancellationApplier}.
 */
@Component
public class HighLevelUpdater {

    private static final short KIND_SESSION = 0;

    private final JdbcClient jdbc;

    public HighLevelUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Applies one projected transaction's effect on the page index. A move is a delete plus an insert, so callers
     * must supply the transaction.
     *
     * @param rollup what {@link RollupUpdater} did for this projected transaction ({@link RollupResult#NONE} for
     *               unsessioned transactions and cancellations)
     * @return the views where a row was inserted, where an existing session row moved or
     *         changed product, and where a hidden session row was revived; see
     *         {@link HighLevelResult}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HighLevelResult applyTransaction(ProjectionTransaction transaction, RollupResult rollup) {
        if (transaction.isCancellation()) {
            return HighLevelResult.NONE;
        }
        if (!transaction.isSessioned()) {
            return new HighLevelResult(
                    insertForSource(transaction, transaction.occurredAt(), (short) transaction.source().code(),
                            transaction.sourceId(), transaction.productId(), transaction.productType(), transaction.currency()),
                    Set.of(), null, null, Map.of(), Set.of());
        }
        if (rollup.sessionStart() == null) {
            throw new IllegalStateException(
                    "sessioned transaction " + transaction.sourceId() + " has no session key in its RollupResult");
        }
        Map<ViewKind, Boolean> movedVisible = HighLevelResult.newViewMap();
        boolean startMoved = rollup.sessionKeyChanged()
                && !rollup.sessionStart().equals(rollup.previousSessionStart());
        if (rollup.sessionKeyChanged()) {
            for (ViewKind view : ViewKind.values()) {
                Boolean visible = startMoved
                        ? move(view(view), transaction, rollup)
                        : updateProduct(view(view), transaction, rollup);
                if (visible != null) {
                    movedVisible.put(view, visible);
                }
            }
        }
        Set<ViewKind> moved = movedVisible.keySet();
        // Materialise the session row in every view THIS transaction's source feeds, whatever order the
        // session's transactions arrived in (P-1). The transaction is live (cancellations return above), so
        // the session has a surviving transaction in that view and the row is visible.
        Set<ViewKind> inserted = insertForSource(transaction, rollup.sessionStart(), KIND_SESSION,
                transaction.batchId(), rollup.sessionProductId(), rollup.sessionProductType(),
                rollup.sessionCurrency());
        // Where the row already existed, it may have been hidden by cancels; this live transaction
        // makes the session visible again in the views its source feeds.
        Set<ViewKind> revived = HighLevelResult.newViewSet();
        for (ViewKind view : transaction.source().views()) {
            if (!inserted.contains(view) && revive(view(view), transaction, rollup)) {
                revived.add(view);
            }
        }
        return new HighLevelResult(inserted, moved,
                moved.isEmpty() ? null : rollup.previousSessionStart(),
                moved.isEmpty() ? null : rollup.previousSessionProductType(),
                movedVisible, revived);
    }

    /** Flips a hidden session row visible at the session's current key; true iff it flipped. */
    private boolean revive(View v, ProjectionTransaction transaction, RollupResult rollup) {
        return jdbc.sql(v.revive())
                .params(transaction.userId(), timestamptz(rollup.sessionStart()), KIND_SESSION, transaction.batchId())
                .update() > 0;
    }

    private Set<ViewKind> insertForSource(ProjectionTransaction transaction, Instant at, short kind,
                                          String refId, String productId, String productType,
                                          String currency) {
        Set<ViewKind> inserted = HighLevelResult.newViewSet();
        for (ViewKind view : transaction.source().views()) {
            if (insert(view(view), transaction.userId(), at, kind, refId, productId, productType,
                    currency, true) > 0) {
                inserted.add(view);
            }
        }
        return inserted;
    }

    private int insert(View v, long userId, Instant at, short kind, String refId,
                       String productId, String productType, String currency, boolean visible) {
        return jdbc.sql(v.insert())
                .params(userId, timestamptz(at), kind, refId, text(productId), text(productType),
                        text(currency), visible)
                .update();
    }

    /**
     * Moves the session row to its new key.
     *
     * @return the moved row's visibility, or null when this view held no row
     */
    private Boolean move(View v, ProjectionTransaction transaction, RollupResult rollup) {
        Boolean visible = jdbc.sql(v.delete())
                .params(transaction.userId(), timestamptz(rollup.previousSessionStart()), KIND_SESSION,
                        transaction.batchId())
                .query(Boolean.class)
                .optional()
                .orElse(null);
        if (visible == null) {
            return null;
        }
        insert(v, transaction.userId(), rollup.sessionStart(), KIND_SESSION, transaction.batchId(),
                rollup.sessionProductId(), rollup.sessionProductType(), rollup.sessionCurrency(),
                visible);
        return visible;
    }

    /**
     * Updates productId and productType in place.
     *
     * @return the row's visibility, or null when this view held no row
     */
    private Boolean updateProduct(View v, ProjectionTransaction transaction, RollupResult rollup) {
        return jdbc.sql(v.updateProduct())
                .params(text(rollup.sessionProductId()), text(rollup.sessionProductType()),
                        transaction.userId(), timestamptz(rollup.sessionStart()), KIND_SESSION, transaction.batchId())
                .query(Boolean.class)
                .optional()
                .orElse(null);
    }

    private static View view(ViewKind kind) {
        return kind == ViewKind.WEB ? View.WEB : View.CC;
    }

    /** The two page-index tables share a column shape; this carries one table's statements. */
    private record View(String insert, String revive, String delete, String updateProduct) {

        static final View WEB = of("wqPrjWebHighLevel");
        static final View CC = of("wqPrjCcHighLevel");

        private static final String KEY = """
                "userId" = ? and "occurredAt" = ? and "kind" = ? and "refId" = ?""";

        static View of(String table) {
            return new View(
                    """
                    insert into "%s" ("userId", "occurredAt", "kind", "refId", "productId",
                            "productType", "currency", "visibleExclCancelled")
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing
                    """.formatted(table),
                    """
                    update "%s" set "visibleExclCancelled" = true
                    where %s and "visibleExclCancelled" = false
                    """.formatted(table, KEY),
                    """
                    delete from "%s"
                    where %s
                    returning "visibleExclCancelled"
                    """.formatted(table, KEY),
                    """
                    update "%s" set "productId" = ?, "productType" = ?
                    where %s
                    returning "visibleExclCancelled"
                    """.formatted(table, KEY));
        }
    }
}
