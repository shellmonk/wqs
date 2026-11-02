package wqs.projection;

import static wqs.db.generated.Tables.WQ_CC_HIGH_LEVEL;
import static wqs.db.generated.Tables.WQ_WEB_HIGH_LEVEL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableField;
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

    private final DSLContext dsl;

    public HighLevelUpdater(DSLContext dsl) {
        this.dsl = dsl;
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
        return dsl.update(v.table)
                .set(v.visible, true)
                .where(v.userId.eq(item.userId()),
                       v.occurredAt.eq(utc(rollup.sessionStart())),
                       v.kind.eq(KIND_SESSION),
                       v.refId.eq(item.batchId()),
                       v.visible.isFalse())
                .execute() > 0;
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
        return dsl.insertInto(v.table)
                .set(v.userId, userId)
                .set(v.occurredAt, utc(at))
                .set(v.kind, kind)
                .set(v.refId, refId)
                .set(v.productId, productId)
                .set(v.productType, productType)
                .set(v.currency, currency)
                .set(v.visible, visible)
                .onConflictDoNothing()
                .execute();
    }

    /**
     * Moves the session row to its new key.
     *
     * @return the moved row's visibility, or null when this view held no row
     */
    private Boolean move(View v, ProjectionItem item, RollupResult rollup) {
        var old = dsl.deleteFrom(v.table)
                .where(v.userId.eq(item.userId()),
                       v.occurredAt.eq(utc(rollup.previousSessionStart())),
                       v.kind.eq(KIND_SESSION),
                       v.refId.eq(item.batchId()))
                .returning(v.visible)
                .fetchOne();
        if (old == null) {
            return null;
        }
        insert(v, item.userId(), rollup.sessionStart(), KIND_SESSION, item.batchId(),
                rollup.sessionProductId(), rollup.sessionProductType(), rollup.sessionCurrency(),
                old.get(v.visible));
        return old.get(v.visible);
    }

    /**
     * Updates productId and productType in place.
     *
     * @return the row's visibility, or null when this view held no row
     */
    private Boolean updateProduct(View v, ProjectionItem item, RollupResult rollup) {
        var row = dsl.update(v.table)
                .set(v.productId, rollup.sessionProductId())
                .set(v.productType, rollup.sessionProductType())
                .where(v.userId.eq(item.userId()),
                       v.occurredAt.eq(utc(rollup.sessionStart())),
                       v.kind.eq(KIND_SESSION),
                       v.refId.eq(item.batchId()))
                .returning(v.visible)
                .fetchOne();
        return row == null ? null : row.get(v.visible);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static View view(ViewKind kind) {
        return kind == ViewKind.WEB ? View.WEB : View.CC;
    }

    /** The two page-index tables share a column shape; this selects one by view. */
    private record View(
            Table<?> table,
            TableField<?, Long> userId,
            TableField<?, OffsetDateTime> occurredAt,
            TableField<?, Short> kind,
            TableField<?, String> refId,
            TableField<?, String> productId,
            TableField<?, String> productType,
            TableField<?, String> currency,
            TableField<?, Boolean> visible) {

        static final View WEB = new View(WQ_WEB_HIGH_LEVEL,
                WQ_WEB_HIGH_LEVEL.userId, WQ_WEB_HIGH_LEVEL.occurredAt, WQ_WEB_HIGH_LEVEL.kind,
                WQ_WEB_HIGH_LEVEL.refId, WQ_WEB_HIGH_LEVEL.productId, WQ_WEB_HIGH_LEVEL.productType,
                WQ_WEB_HIGH_LEVEL.currency,
                WQ_WEB_HIGH_LEVEL.visibleExclCancelled);
        static final View CC = new View(WQ_CC_HIGH_LEVEL,
                WQ_CC_HIGH_LEVEL.userId, WQ_CC_HIGH_LEVEL.occurredAt, WQ_CC_HIGH_LEVEL.kind,
                WQ_CC_HIGH_LEVEL.refId, WQ_CC_HIGH_LEVEL.productId, WQ_CC_HIGH_LEVEL.productType,
                WQ_CC_HIGH_LEVEL.currency,
                WQ_CC_HIGH_LEVEL.visibleExclCancelled);
    }
}
