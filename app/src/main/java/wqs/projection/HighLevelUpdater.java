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
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;

/**
 * Maintains the level-1/2 page index (spec 5.6): exactly one row per session and one per
 * unsessioned item.
 *
 * A session row is keyed and populated from the SESSION's current key in {@link RollupResult}
 * (its start and product), never from the triggering item: a session's start moves earlier when
 * an earlier item arrives, so the row is MOVED (the start is in the primary key and the partition
 * key) and its productId / productType updated in place when only the product changed. A transaction joining a
 * session with an unchanged key inserts nothing in views that already hold the row, which keeps
 * page counts equal to the number of high-level items rather than the number of transactions.
 * Every sessioned item also ensures the row exists in each view its own source feeds, so the end
 * state does not depend on which source opened the session.
 *
 * Visibility ("visibleExclCancelled") is per view: a session row in view V is visible while the
 * session has at least one surviving (non-cancelled, non-cancellation) item whose source feeds V.
 * {@link CancellationApplier} hides a row when a cancel removes the last such item; here, a live
 * item joining a session whose row in a view its source feeds is hidden REVIVES it, since the
 * item is itself a surviving item in that view.
 *
 * Currency: a session row carries the SESSION's currency (from {@link RollupResult#sessionCurrency},
 * exactly as "wqSession" holds it). A session is single-currency -- an item of another currency
 * is dead-lettered before it gets here (plan D6) -- so the currency never changes and rows never
 * need re-syncing. An unsessioned row carries its item's own currency.
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
     * Applies one item's effect on the page index. A move is a delete plus an insert, so callers
     * must supply the transaction.
     *
     * @param rollup what {@link RollupUpdater} did for this item ({@link RollupResult#NONE} for
     *               unsessioned items and cancellations)
     * @return the views where a row was inserted, where an existing session row moved or
     *         changed product, and where a hidden session row was revived; see
     *         {@link HighLevelResult}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HighLevelResult applyItem(ProjectionItem item, RollupResult rollup) {
        if (item.isCancellation()) {
            return HighLevelResult.NONE;
        }
        if (!item.isSessioned()) {
            return new HighLevelResult(
                    insertForSource(item, item.occurredAt(), (short) item.source().code(),
                            item.sourceId(), item.productId(), item.productType(), item.currency()),
                    Set.of(), null, null, Map.of(), Set.of());
        }
        if (rollup.sessionStart() == null) {
            throw new IllegalStateException(
                    "sessioned item " + item.sourceId() + " has no session key in its RollupResult");
        }
        Map<ViewKind, Boolean> movedVisible = HighLevelResult.newViewMap();
        boolean startMoved = rollup.sessionKeyChanged()
                && !rollup.sessionStart().equals(rollup.previousSessionStart());
        if (rollup.sessionKeyChanged()) {
            for (ViewKind view : ViewKind.values()) {
                Boolean visible = startMoved
                        ? move(view(view), item, rollup)
                        : updateProduct(view(view), item, rollup);
                if (visible != null) {
                    movedVisible.put(view, visible);
                }
            }
        }
        Set<ViewKind> moved = movedVisible.keySet();
        // Materialise the session row in every view THIS item's source feeds, whatever order the
        // session's items arrived in (P-1). The item is live (cancellations return above), so
        // the session has a surviving item in that view and the row is visible.
        Set<ViewKind> inserted = insertForSource(item, rollup.sessionStart(), KIND_SESSION,
                item.batchId(), rollup.sessionProductId(), rollup.sessionProductType(),
                rollup.sessionCurrency());
        // Where the row already existed, it may have been hidden by cancels; this live item
        // makes the session visible again in the views its source feeds.
        Set<ViewKind> revived = HighLevelResult.newViewSet();
        for (ViewKind view : item.source().views()) {
            if (!inserted.contains(view) && revive(view(view), item, rollup)) {
                revived.add(view);
            }
        }
        return new HighLevelResult(inserted, moved,
                moved.isEmpty() ? null : rollup.previousSessionStart(),
                moved.isEmpty() ? null : rollup.previousSessionProductType(),
                movedVisible, revived);
    }

    /** Flips a hidden session row visible at the session's current key; true iff it flipped. */
    private boolean revive(View v, ProjectionItem item, RollupResult rollup) {
        return jdbc.sql(v.revive())
                .params(item.userId(), timestamptz(rollup.sessionStart()), KIND_SESSION, item.batchId())
                .update() > 0;
    }

    private Set<ViewKind> insertForSource(ProjectionItem item, Instant at, short kind,
                                          String refId, String productId, String productType,
                                          String currency) {
        Set<ViewKind> inserted = HighLevelResult.newViewSet();
        for (ViewKind view : item.source().views()) {
            if (insert(view(view), item.userId(), at, kind, refId, productId, productType,
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
    private Boolean move(View v, ProjectionItem item, RollupResult rollup) {
        Boolean visible = jdbc.sql(v.delete())
                .params(item.userId(), timestamptz(rollup.previousSessionStart()), KIND_SESSION,
                        item.batchId())
                .query(Boolean.class)
                .optional()
                .orElse(null);
        if (visible == null) {
            return null;
        }
        insert(v, item.userId(), rollup.sessionStart(), KIND_SESSION, item.batchId(),
                rollup.sessionProductId(), rollup.sessionProductType(), rollup.sessionCurrency(),
                visible);
        return visible;
    }

    /**
     * Updates productId and productType in place.
     *
     * @return the row's visibility, or null when this view held no row
     */
    private Boolean updateProduct(View v, ProjectionItem item, RollupResult rollup) {
        return jdbc.sql(v.updateProduct())
                .params(text(rollup.sessionProductId()), text(rollup.sessionProductType()),
                        item.userId(), timestamptz(rollup.sessionStart()), KIND_SESSION, item.batchId())
                .query(Boolean.class)
                .optional()
                .orElse(null);
    }

    private static View view(ViewKind kind) {
        return kind == ViewKind.WEB ? View.WEB : View.CC;
    }

    /** The two page-index tables share a column shape; this carries one table's statements. */
    private record View(String insert, String revive, String delete, String updateProduct) {

        static final View WEB = of("wqWebHighLevel");
        static final View CC = of("wqCcHighLevel");

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
