package wqs.projection;

import static wqs.db.generated.Tables.WQ_CC_HIGH_LEVEL;
import static wqs.db.generated.Tables.WQ_CC_ITEM;
import static wqs.db.generated.Tables.WQ_GROUP_LOCATOR;
import static wqs.db.generated.Tables.WQ_ITEM_LOCATOR;
import static wqs.db.generated.Tables.WQ_PLAY;
import static wqs.db.generated.Tables.WQ_SESSION;
import static wqs.db.generated.Tables.WQ_WEB_HIGH_LEVEL;
import static wqs.db.generated.Tables.WQ_WEB_ITEM;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.db.generated.tables.records.WqCcItemRecord;
import wqs.db.generated.tables.records.WqSessionRecord;
import wqs.domain.MoneyDirection;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

/**
 * Applies a cancellation to an already-projected item (spec 6.3), or parks it until the item
 * arrives.
 *
 * <p><b>Resolution.</b> A cancel targets an item of its OWN source: a TRANSACTION cancel cancels
 * a TRANSACTION, a PAYMENT cancel a PAYMENT. The original is therefore resolved through
 * "wqItemLocator" by (cancel.source, cancel.cancels) -- the locator's own key -- never by sourceId
 * alone, which different sources may share. The same-source assumption is to be confirmed with
 * the producer (O-13).
 *
 * <p><b>Effects</b>, each the exact inverse of what the original contributed when it arrived:
 * <ul>
 *   <li>Item rows: "cancelled" = true in every view the original's source feeds; the ITEM
 *       excl-cancelled counter loses one in each, at the original's own (occurredAt, productId).
 *       cntAll is untouched -- a cancelled item is still returned when the filter is off.</li>
 *   <li>Rollups (sessioned original): txCountExclCancelled -1 and the original's own money
 *       (computed exactly as {@link RollupUpdater} added it) on its play and its session; the
 *       session's playCountExclCancelled -1 when the play's txCountExclCancelled reaches 0.</li>
 *   <li>Page index, PER VIEW: a session row in view V is visible while the session has at least
 *       one surviving (non-cancelled, non-cancellation) item whose source feeds V. For each view
 *       the original's source feeds, that is re-evaluated against the item table; if nothing
 *       survives, the row flips to hidden and the HIGH_LEVEL excl-cancelled counter loses one at
 *       the SESSION's key (start and productId from the session row, never the original's). An
 *       unsessioned original's own row flips at the item's key. A live item joining later
 *       revives the row ({@link HighLevelUpdater}).</li>
 * </ul>
 * The session's key is not touched: startedAt and productId fold over every item, cancelled or
 * not, so a cancel never moves a row.
 *
 * <p><b>Idempotence.</b> The writer's locator gate stops a replayed cancel before it gets here.
 * A different cancel of an already-cancelled item changes no tally; it only lowers
 * "cancelledBy" to the smallest cancel id, so the stored state does not depend on which of two
 * cancels arrived first (P-1).
 *
 * <p>Strict, like the counters: every update here targets a row the original's own arrival
 * created, so a missing row or a negative is a bug and fails the transaction. No clamping.
 */
@Component
public class CancellationApplier {

    private static final short KIND_SESSION = 0;
    private static final String SESSION_MARKER = "";

    private final DSLContext dsl;
    private final CounterUpdater counterUpdater;
    private final PendingCancelStore pendingCancelStore;

    public CancellationApplier(
            DSLContext dsl, CounterUpdater counterUpdater, PendingCancelStore pendingCancelStore) {
        this.dsl = dsl;
        this.counterUpdater = counterUpdater;
        this.pendingCancelStore = pendingCancelStore;
    }

    /**
     * Callers supply the transaction: the effects below must commit with the cancel's own insert.
     *
     * @return true if applied (or already applied); false if the original is unknown and the
     *         cancel was parked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(ProjectionItem cancel) {
        if (!cancel.isCancellation()) {
            throw new IllegalArgumentException("not a cancellation: " + cancel.sourceId());
        }
        WqCcItemRecord original = findOriginal(cancel);
        if (original == null) {
            pendingCancelStore.park(cancel);
            return false;
        }

        SourceKind source = SourceKind.ofCode(original.getSource());
        if (original.getCancelled()) {
            lowerCancelledBy(cancel, original, source);
            return true;
        }
        markCancelled(cancel, original, source);

        long userId = original.getUserId();
        Instant occurredAt = original.getOccurredAt().toInstant();
        for (ViewKind view : source.views()) {
            counterUpdater.uncountExclCancelled(
                    userId, view, PageUnit.ITEM, original.getProductId(), occurredAt);
        }

        // A cancellation contributed to no rollup and no page unit, so cancelling one (should a
        // producer ever do so) has only the item-level effect above.
        if (isCancellation(original.getCancels())) {
            return true;
        }

        if (original.getBatchId() == null) {
            for (ViewKind view : source.views()) {
                hide(view, userId, occurredAt, (short) source.code(), original.getSourceId(),
                        original.getProductId());
            }
            return true;
        }

        WqSessionRecord session = subtractFromRollups(original);
        for (ViewKind view : source.views()) {
            if (!sessionSurvivesIn(view, session)) {
                hide(view, userId, session.getStartedAt().toInstant(), KIND_SESSION,
                        session.getBatchId(), session.getProductId());
            }
        }
        return true;
    }

    /** The original, read from "wqCcItem" -- every source feeds /cc -- or null if not yet seen. */
    private WqCcItemRecord findOriginal(ProjectionItem cancel) {
        Record2<Long, OffsetDateTime> located = dsl
                .select(WQ_ITEM_LOCATOR.userId, WQ_ITEM_LOCATOR.occurredAt)
                .from(WQ_ITEM_LOCATOR)
                .where(WQ_ITEM_LOCATOR.source.eq((short) cancel.source().code()),
                       WQ_ITEM_LOCATOR.sourceId.eq(cancel.cancels()))
                .fetchOne();
        if (located == null) {
            return null;
        }
        return dsl.selectFrom(WQ_CC_ITEM)
                .where(WQ_CC_ITEM.userId.eq(located.value1()),
                       WQ_CC_ITEM.occurredAt.eq(located.value2()),
                       WQ_CC_ITEM.source.eq((short) cancel.source().code()),
                       WQ_CC_ITEM.sourceId.eq(cancel.cancels()))
                .fetchSingle();
    }

    private void markCancelled(ProjectionItem cancel, WqCcItemRecord original, SourceKind source) {
        for (ViewKind view : source.views()) {
            ItemTable t = ItemTable.of(view);
            int rows = dsl.update(t.table())
                    .set(t.cancelled(), true)
                    .set(t.cancelledBy(), cancel.sourceId())
                    .where(t.identifies(original))
                    .execute();
            requireOne(rows, "item row in " + view, original);
        }
    }

    private void lowerCancelledBy(ProjectionItem cancel, WqCcItemRecord original, SourceKind source) {
        for (ViewKind view : source.views()) {
            ItemTable t = ItemTable.of(view);
            dsl.update(t.table())
                    .set(t.cancelledBy(), DSL.least(t.cancelledBy(), DSL.val(cancel.sourceId())))
                    .where(t.identifies(original))
                    .execute();
        }
    }

    /**
     * Subtracts the original's own contribution from its play and session ExclCancelled columns.
     *
     * @return the session row after the update (its current key and bounds)
     */
    private WqSessionRecord subtractFromRollups(WqCcItemRecord original) {
        long userId = original.getUserId();
        String batchId = original.getBatchId();
        // Mirrors RollupUpdater exactly: direction by type, magnitude by abs(amount).
        MoneyDirection direction = MoneyDirection.ofType(original.getType());
        BigDecimal amount = original.getAmount().abs();
        BigDecimal plays = direction == MoneyDirection.PLAY ? amount : BigDecimal.ZERO;
        BigDecimal wins = direction == MoneyDirection.WIN ? amount : BigDecimal.ZERO;
        BigDecimal net = wins.subtract(plays);

        boolean playEmptied = false;
        if (original.getSubBatchId() != null) {
            String subBatchId = original.getSubBatchId();
            int survivors = dsl.update(WQ_PLAY)
                    .set(WQ_PLAY.txCountExclCancelled, WQ_PLAY.txCountExclCancelled.minus(1))
                    .set(WQ_PLAY.totalPlaysExclCancelled, WQ_PLAY.totalPlaysExclCancelled.minus(plays))
                    .set(WQ_PLAY.totalWinsExclCancelled, WQ_PLAY.totalWinsExclCancelled.minus(wins))
                    .set(WQ_PLAY.netExclCancelled, WQ_PLAY.netExclCancelled.minus(net))
                    .where(WQ_PLAY.userId.eq(userId),
                           WQ_PLAY.startedAt.eq(groupStart(userId, batchId, subBatchId)),
                           WQ_PLAY.batchId.eq(batchId),
                           WQ_PLAY.subBatchId.eq(subBatchId))
                    .returning(WQ_PLAY.txCountExclCancelled)
                    .fetchSingle()
                    .getTxCountExclCancelled();
            requireNonNegative(survivors, "play txCountExclCancelled", original);
            playEmptied = survivors == 0;
        }

        WqSessionRecord session = dsl.update(WQ_SESSION)
                .set(WQ_SESSION.txCountExclCancelled, WQ_SESSION.txCountExclCancelled.minus(1))
                .set(WQ_SESSION.playCountExclCancelled,
                        WQ_SESSION.playCountExclCancelled.minus(playEmptied ? 1 : 0))
                .set(WQ_SESSION.totalPlaysExclCancelled, WQ_SESSION.totalPlaysExclCancelled.minus(plays))
                .set(WQ_SESSION.totalWinsExclCancelled, WQ_SESSION.totalWinsExclCancelled.minus(wins))
                .set(WQ_SESSION.netExclCancelled, WQ_SESSION.netExclCancelled.minus(net))
                .where(WQ_SESSION.userId.eq(userId),
                       WQ_SESSION.startedAt.eq(groupStart(userId, batchId, SESSION_MARKER)),
                       WQ_SESSION.batchId.eq(batchId))
                .returning()
                .fetchSingle();
        requireNonNegative(session.getTxCountExclCancelled(), "session txCountExclCancelled", original);
        requireNonNegative(session.getPlayCountExclCancelled(), "session playCountExclCancelled", original);
        return session;
    }

    private OffsetDateTime groupStart(long userId, String batchId, String subBatchId) {
        return dsl.select(WQ_GROUP_LOCATOR.startedAt)
                .from(WQ_GROUP_LOCATOR)
                .where(WQ_GROUP_LOCATOR.userId.eq(userId),
                       WQ_GROUP_LOCATOR.batchId.eq(batchId),
                       WQ_GROUP_LOCATOR.subBatchId.eq(subBatchId))
                .fetchSingle(WQ_GROUP_LOCATOR.startedAt);
    }

    /**
     * Whether the session keeps a surviving item in {@code view}. Every item of the session lies
     * in [startedAt, endedAt] (both fold over all of them), so the bound loses nothing and lets
     * the partitioned item table prune.
     */
    private boolean sessionSurvivesIn(ViewKind view, WqSessionRecord session) {
        ItemTable t = ItemTable.of(view);
        return dsl.fetchExists(DSL.selectOne()
                .from(t.table())
                .where(t.userId().eq(session.getUserId()),
                       t.occurredAt().between(session.getStartedAt(), session.getEndedAt()),
                       t.batchId().eq(session.getBatchId()),
                       t.cancelled().isFalse(),
                       t.cancels().isNull().or(DSL.trim(t.cancels()).eq(""))));
    }

    /**
     * Hides one page-index row and takes it out of the HIGH_LEVEL excl-cancelled tally. The row
     * was visible -- the original was a surviving item in this view until now -- so anything but
     * exactly one flip is a bug.
     */
    private void hide(ViewKind view, long userId, Instant at, short kind, String refId,
                      Integer productId) {
        PageTable p = PageTable.of(view);
        int rows = dsl.update(p.table())
                .set(p.visible(), false)
                .where(p.userId().eq(userId),
                       p.occurredAt().eq(at.atOffset(ZoneOffset.UTC)),
                       p.kind().eq(kind),
                       p.refId().eq(refId),
                       p.visible().isTrue())
                .execute();
        if (rows != 1) {
            throw new IllegalStateException("expected to hide exactly one visible page-index row,"
                    + " hid " + rows + ": view=" + view + " userId=" + userId + " at=" + at
                    + " kind=" + kind + " refId=" + refId);
        }
        counterUpdater.uncountExclCancelled(userId, view, PageUnit.HIGH_LEVEL, productId, at);
    }

    private static boolean isCancellation(String cancels) {
        return cancels != null && !cancels.isBlank();
    }

    private static void requireOne(int rows, String what, WqCcItemRecord original) {
        if (rows != 1) {
            throw new IllegalStateException("expected exactly one " + what + ", got " + rows
                    + ": " + describe(original));
        }
    }

    private static void requireNonNegative(int value, String what, WqCcItemRecord original) {
        if (value < 0) {
            throw new IllegalStateException(what + " went negative (" + value
                    + ") cancelling " + describe(original));
        }
    }

    private static String describe(WqCcItemRecord original) {
        return "userId=" + original.getUserId() + " source=" + original.getSource()
                + " sourceId=" + original.getSourceId() + " occurredAt=" + original.getOccurredAt();
    }

    /** The two item tables share a column shape; this selects one by view. */
    private record ItemTable(
            Table<?> table,
            TableField<?, Long> userId,
            TableField<?, OffsetDateTime> occurredAt,
            TableField<?, Short> source,
            TableField<?, String> sourceId,
            TableField<?, String> batchId,
            TableField<?, Boolean> cancelled,
            TableField<?, String> cancelledBy,
            TableField<?, String> cancels) {

        static final ItemTable WEB = new ItemTable(WQ_WEB_ITEM,
                WQ_WEB_ITEM.userId, WQ_WEB_ITEM.occurredAt, WQ_WEB_ITEM.source,
                WQ_WEB_ITEM.sourceId, WQ_WEB_ITEM.batchId, WQ_WEB_ITEM.cancelled,
                WQ_WEB_ITEM.cancelledBy, WQ_WEB_ITEM.cancels);
        static final ItemTable CC = new ItemTable(WQ_CC_ITEM,
                WQ_CC_ITEM.userId, WQ_CC_ITEM.occurredAt, WQ_CC_ITEM.source,
                WQ_CC_ITEM.sourceId, WQ_CC_ITEM.batchId, WQ_CC_ITEM.cancelled,
                WQ_CC_ITEM.cancelledBy, WQ_CC_ITEM.cancels);

        static ItemTable of(ViewKind view) {
            return view == ViewKind.WEB ? WEB : CC;
        }

        /** The original's full identity: partition key first, so the update prunes. */
        Condition identifies(WqCcItemRecord original) {
            return userId.eq(original.getUserId())
                    .and(occurredAt.eq(original.getOccurredAt()))
                    .and(source.eq(original.getSource()))
                    .and(sourceId.eq(original.getSourceId()));
        }
    }

    /** The two page-index tables share a column shape; this selects one by view. */
    private record PageTable(
            Table<?> table,
            TableField<?, Long> userId,
            TableField<?, OffsetDateTime> occurredAt,
            TableField<?, Short> kind,
            TableField<?, String> refId,
            TableField<?, Boolean> visible) {

        static final PageTable WEB = new PageTable(WQ_WEB_HIGH_LEVEL,
                WQ_WEB_HIGH_LEVEL.userId, WQ_WEB_HIGH_LEVEL.occurredAt, WQ_WEB_HIGH_LEVEL.kind,
                WQ_WEB_HIGH_LEVEL.refId, WQ_WEB_HIGH_LEVEL.visibleExclCancelled);
        static final PageTable CC = new PageTable(WQ_CC_HIGH_LEVEL,
                WQ_CC_HIGH_LEVEL.userId, WQ_CC_HIGH_LEVEL.occurredAt, WQ_CC_HIGH_LEVEL.kind,
                WQ_CC_HIGH_LEVEL.refId, WQ_CC_HIGH_LEVEL.visibleExclCancelled);

        static PageTable of(ViewKind view) {
            return view == ViewKind.WEB ? WEB : CC;
        }
    }
}
