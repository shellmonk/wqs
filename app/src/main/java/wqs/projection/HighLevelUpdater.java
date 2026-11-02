package wqs.projection;

import static wqs.db.generated.Tables.WQ_CC_HIGH_LEVEL;
import static wqs.db.generated.Tables.WQ_WEB_HIGH_LEVEL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * (its start and productId), never from the triggering item: a session's start moves earlier when
 * an earlier item arrives, so the row is MOVED (the start is in the primary key and the partition
 * key) and its productId updated in place when only the product changed. A transaction joining a
 * session with an unchanged key inserts nothing, which keeps page counts equal to the number of
 * high-level items rather than the number of transactions.
 *
 * A cancellation never creates a row: cancellation effects are applied by a later task.
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
     * @return number of page-index rows inserted; moves and in-place updates return 0
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyItem(ProjectionItem item, RollupResult rollup) {
        if (item.isCancellation()) {
            return 0;
        }
        if (!item.isSessioned() || rollup.sessionStart() == null) {
            return insertForSource(item, item.occurredAt(), (short) item.source().code(),
                    item.sourceId(), item.productId());
        }
        if (rollup.sessionIsNew()) {
            return insertForSource(item, rollup.sessionStart(), KIND_SESSION,
                    item.batchId(), rollup.sessionProductId());
        }
        if (rollup.sessionKeyChanged()) {
            for (ViewKind view : ViewKind.values()) {
                if (!rollup.sessionStart().equals(rollup.previousSessionStart())) {
                    move(view(view), item, rollup);
                } else {
                    updateProduct(view(view), item, rollup);
                }
            }
        }
        return 0;
    }

    private int insertForSource(ProjectionItem item, Instant at, short kind, String refId,
                                Integer productId) {
        int inserted = 0;
        for (ViewKind view : item.source().views()) {
            inserted += insert(view(view), item.userId(), at, kind, refId, productId,
                    item.currency(), true);
        }
        return inserted;
    }

    private int insert(View v, long userId, Instant at, short kind, String refId,
                       Integer productId, String currency, boolean visible) {
        return dsl.insertInto(v.table)
                .set(v.userId, userId)
                .set(v.occurredAt, utc(at))
                .set(v.kind, kind)
                .set(v.refId, refId)
                .set(v.productId, productId)
                .set(v.currency, currency)
                .set(v.visible, visible)
                .onConflictDoNothing()
                .execute();
    }

    /** Moves the session row to its new start, in this view only if the row exists there. */
    private void move(View v, ProjectionItem item, RollupResult rollup) {
        var old = dsl.deleteFrom(v.table)
                .where(v.userId.eq(item.userId()),
                       v.occurredAt.eq(utc(rollup.previousSessionStart())),
                       v.kind.eq(KIND_SESSION),
                       v.refId.eq(item.batchId()))
                .returning(v.visible, v.currency)
                .fetchOne();
        if (old != null) {
            insert(v, item.userId(), rollup.sessionStart(), KIND_SESSION, item.batchId(),
                    rollup.sessionProductId(), old.get(v.currency), old.get(v.visible));
        }
    }

    private void updateProduct(View v, ProjectionItem item, RollupResult rollup) {
        dsl.update(v.table)
                .set(v.productId, rollup.sessionProductId())
                .where(v.userId.eq(item.userId()),
                       v.occurredAt.eq(utc(rollup.sessionStart())),
                       v.kind.eq(KIND_SESSION),
                       v.refId.eq(item.batchId()))
                .execute();
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
            TableField<?, Integer> productId,
            TableField<?, String> currency,
            TableField<?, Boolean> visible) {

        static final View WEB = new View(WQ_WEB_HIGH_LEVEL,
                WQ_WEB_HIGH_LEVEL.userId, WQ_WEB_HIGH_LEVEL.occurredAt, WQ_WEB_HIGH_LEVEL.kind,
                WQ_WEB_HIGH_LEVEL.refId, WQ_WEB_HIGH_LEVEL.productId, WQ_WEB_HIGH_LEVEL.currency,
                WQ_WEB_HIGH_LEVEL.visibleExclCancelled);
        static final View CC = new View(WQ_CC_HIGH_LEVEL,
                WQ_CC_HIGH_LEVEL.userId, WQ_CC_HIGH_LEVEL.occurredAt, WQ_CC_HIGH_LEVEL.kind,
                WQ_CC_HIGH_LEVEL.refId, WQ_CC_HIGH_LEVEL.productId, WQ_CC_HIGH_LEVEL.currency,
                WQ_CC_HIGH_LEVEL.visibleExclCancelled);
    }
}
