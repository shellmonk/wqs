package wqs.projection;

import static wqs.db.generated.Tables.WQ_GROUP_LOCATOR;
import static wqs.db.generated.Tables.WQ_PLAY;
import static wqs.db.generated.Tables.WQ_SESSION;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.jooq.Record2;
import wqs.db.TxTypeRegistry;
import wqs.db.generated.tables.records.WqPlayRecord;
import wqs.db.generated.tables.records.WqSessionRecord;
import wqs.domain.MoneyDirection;
import wqs.domain.ProjectionItem;

/**
 * Maintains session and play aggregates incrementally (spec 5.5). Only ever called for items the
 * locator gate accepted as new and that are not cancellations, so incrementing is safe -- a
 * replayed event never reaches here.
 *
 * "totalPlays" is a monetary amount (the sum wagered), not a count. The count metric is
 * "playCount" on a session and "txCount" on a play.
 *
 * Order independence (spec P-1): every column is a commutative, associative fold over the
 * group's items, so any arrival order converges to the same row.
 * <ul>
 *   <li>"startedAt" = earliest occurredAt. It is the partition key and part of the primary key,
 *       so an item earlier than the current anchor RE-ANCHORS the group: the row is deleted,
 *       re-inserted at the new startedAt with its aggregates intact, the item is then folded in
 *       by the ordinary upsert, and the locator is pointed at the new startedAt. Play and session
 *       re-anchor independently.</li>
 *   <li>"endedAt" = latest occurredAt (GREATEST).</li>
 *   <li>counts and money = sums. The ExclCancelled columns are the same sums over surviving
 *       items: {@link CancellationApplier} subtracts a cancelled item's own contribution, and
 *       "playCountExclCancelled" counts plays with a surviving transaction -- so a live item
 *       joining a play that cancels had emptied brings it back, not only a new play.</li>
 *   <li>"productId", "productType" and "currency" = each LEAST over the group's non-null values.
 *       A group's items are expected to agree; if they do not, the smallest value wins regardless
 *       of arrival order rather than whichever item happened to arrive first.</li>
 * </ul>
 */
@Component
public class RollupUpdater {

    /**
     * The "subBatchId" of a session's own locator row. Unreachable from input: ProjectionItem
     * normalises a blank subBatchId to null, and an item without a play never claims a play row.
     */
    private static final String SESSION_MARKER = "";

    private final DSLContext dsl;
    private final TxTypeRegistry txTypes;

    public RollupUpdater(DSLContext dsl, TxTypeRegistry txTypes) {
        this.dsl = dsl;
        this.txTypes = txTypes;
    }

    /**
     * Folds one item into its play and session rollups.
     *
     * Only called for items the locator gate accepted as new (so incrementing is safe) and that
     * are not cancellations. A cancellation returns {@link RollupResult#NONE} without touching any
     * rollup: cancellation effects (the ExclCancelled columns) are applied elsewhere, and folding it in
     * here would count a reversed win as a win.
     *
     * A re-anchor is a delete plus an insert plus a locator update; committing only part of it
     * would lose or duplicate a group, so callers must supply the transaction.
     *
     * Lock order: within one call the play's locator row is locked before the session's. A
     * transaction applying several items of the same session can still take those locks in a
     * different order than a concurrent transaction over the same session (item A's play, then
     * the session, then item B's play), which could deadlock. That is safe today because a single
     * sequential consumer applies all items; any future parallel poller or replay path must
     * partition work by session (or by user) before calling this.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RollupResult applyItem(ProjectionItem item) {
        if (!item.isSessioned() || item.isCancellation()) {
            return RollupResult.NONE;
        }

        MoneyDirection direction = txTypes.directionOf(item.type());
        BigDecimal plays = direction == MoneyDirection.PLAY ? item.amount().abs() : BigDecimal.ZERO;
        BigDecimal wins = direction == MoneyDirection.WIN ? item.amount().abs() : BigDecimal.ZERO;
        Contribution contribution = new Contribution(
                item, item.occurredAt().atOffset(ZoneOffset.UTC), plays, wins);

        boolean playIsNew = false;
        boolean playIsLive = false;
        if (item.hasPlay()) {
            Anchor play = claim(item.userId(), item.batchId(), item.subBatchId(), contribution.at());
            if (play.previousStart() != null) {
                movePlay(item, play.previousStart(), play.startedAt());
            }
            int survivors = upsertPlay(contribution, play.startedAt());
            playIsNew = play.isNew();
            // This item is the play's only surviving transaction: the play is new, or every
            // earlier transaction in it was cancelled (spec 6.3). Either way it rejoins the
            // session's playCountExclCancelled.
            playIsLive = survivors == 1;
        }

        Anchor session = claim(item.userId(), item.batchId(), SESSION_MARKER, contribution.at());
        OffsetDateTime oldStart = session.previousStart() != null
                ? session.previousStart()
                : session.startedAt();
        Record2<String, String> oldProduct = session.isNew() ? null : sessionProduct(item, oldStart);
        if (session.previousStart() != null) {
            moveSession(item, session.previousStart(), session.startedAt());
        }
        SessionLabel label = upsertSession(contribution, session.startedAt(), playIsNew, playIsLive);

        boolean keyChanged = !session.isNew()
                && (session.previousStart() != null
                        || !Objects.equals(oldProduct.value1(), label.productId())
                        || !Objects.equals(oldProduct.value2(), label.productType()));
        return new RollupResult(
                session.isNew(),
                playIsNew,
                session.startedAt().toInstant(),
                label.productId(),
                label.productType(),
                label.currency(),
                keyChanged ? oldStart.toInstant() : null,
                keyChanged ? oldProduct.value2() : null,
                keyChanged);
    }

    /** The session's (productId, productType) before this item is folded in. */
    private Record2<String, String> sessionProduct(ProjectionItem item, OffsetDateTime startedAt) {
        return dsl.select(WQ_SESSION.productId, WQ_SESSION.productType)
                .from(WQ_SESSION)
                .where(WQ_SESSION.userId.eq(item.userId()),
                       WQ_SESSION.startedAt.eq(startedAt),
                       WQ_SESSION.batchId.eq(item.batchId()))
                .fetchSingle();
    }

    /**
     * Finds or creates the group's locator row and moves it earlier if this item predates it.
     * The locator row is locked for the rest of the transaction.
     */
    private Anchor claim(long userId, String batchId, String subBatchId, OffsetDateTime at) {
        int created = dsl.insertInto(WQ_GROUP_LOCATOR)
                .set(WQ_GROUP_LOCATOR.userId, userId)
                .set(WQ_GROUP_LOCATOR.batchId, batchId)
                .set(WQ_GROUP_LOCATOR.subBatchId, subBatchId)
                .set(WQ_GROUP_LOCATOR.startedAt, at)
                .onConflictDoNothing()
                .execute();
        if (created == 1) {
            return new Anchor(true, at, null);
        }

        OffsetDateTime current = dsl.select(WQ_GROUP_LOCATOR.startedAt)
                .from(WQ_GROUP_LOCATOR)
                .where(WQ_GROUP_LOCATOR.userId.eq(userId),
                       WQ_GROUP_LOCATOR.batchId.eq(batchId),
                       WQ_GROUP_LOCATOR.subBatchId.eq(subBatchId))
                .forUpdate()
                .fetchSingle(WQ_GROUP_LOCATOR.startedAt);
        if (!at.isBefore(current)) {
            return new Anchor(false, current, null);
        }

        dsl.update(WQ_GROUP_LOCATOR)
                .set(WQ_GROUP_LOCATOR.startedAt, at)
                .where(WQ_GROUP_LOCATOR.userId.eq(userId),
                       WQ_GROUP_LOCATOR.batchId.eq(batchId),
                       WQ_GROUP_LOCATOR.subBatchId.eq(subBatchId))
                .execute();
        return new Anchor(false, at, current);
    }

    /** Re-keys the play row to an earlier startedAt, possibly into another partition. */
    private void movePlay(ProjectionItem item, OffsetDateTime from, OffsetDateTime to) {
        WqPlayRecord row = dsl.deleteFrom(WQ_PLAY)
                .where(WQ_PLAY.userId.eq(item.userId()),
                       WQ_PLAY.startedAt.eq(from),
                       WQ_PLAY.batchId.eq(item.batchId()),
                       WQ_PLAY.subBatchId.eq(item.subBatchId()))
                .returning()
                .fetchSingle();
        row.setStartedAt(to);
        row.changed(true);
        dsl.insertInto(WQ_PLAY).set(row).execute();
    }

    /** Re-keys the session row to an earlier startedAt, possibly into another partition. */
    private void moveSession(ProjectionItem item, OffsetDateTime from, OffsetDateTime to) {
        WqSessionRecord row = dsl.deleteFrom(WQ_SESSION)
                .where(WQ_SESSION.userId.eq(item.userId()),
                       WQ_SESSION.startedAt.eq(from),
                       WQ_SESSION.batchId.eq(item.batchId()))
                .returning()
                .fetchSingle();
        row.setStartedAt(to);
        row.changed(true);
        dsl.insertInto(WQ_SESSION).set(row).execute();
    }

    /** @return the play's txCountExclCancelled after the fold. */
    private int upsertPlay(Contribution c, OffsetDateTime startedAt) {
        ProjectionItem item = c.item();
        return dsl.insertInto(WQ_PLAY)
                .set(WQ_PLAY.userId, item.userId())
                .set(WQ_PLAY.batchId, item.batchId())
                .set(WQ_PLAY.subBatchId, item.subBatchId())
                .set(WQ_PLAY.startedAt, startedAt)
                .set(WQ_PLAY.endedAt, c.at())
                .set(WQ_PLAY.productId, item.productId())
                .set(WQ_PLAY.productType, item.productType())
                .set(WQ_PLAY.currency, item.currency())
                .set(WQ_PLAY.txCount, 1)
                .set(WQ_PLAY.txCountExclCancelled, 1)
                .set(WQ_PLAY.totalPlays, c.plays())
                .set(WQ_PLAY.totalWins, c.wins())
                .set(WQ_PLAY.net, c.net())
                .set(WQ_PLAY.totalPlaysExclCancelled, c.plays())
                .set(WQ_PLAY.totalWinsExclCancelled, c.wins())
                .set(WQ_PLAY.netExclCancelled, c.net())
                .onConflict(WQ_PLAY.userId, WQ_PLAY.startedAt, WQ_PLAY.batchId, WQ_PLAY.subBatchId)
                .doUpdate()
                .set(WQ_PLAY.endedAt, latest(WQ_PLAY.endedAt))
                .set(WQ_PLAY.productId, least(WQ_PLAY.productId))
                .set(WQ_PLAY.productType, least(WQ_PLAY.productType))
                .set(WQ_PLAY.currency, least(WQ_PLAY.currency))
                .set(WQ_PLAY.txCount, summed(WQ_PLAY.txCount))
                .set(WQ_PLAY.txCountExclCancelled, summed(WQ_PLAY.txCountExclCancelled))
                .set(WQ_PLAY.totalPlays, summed(WQ_PLAY.totalPlays))
                .set(WQ_PLAY.totalWins, summed(WQ_PLAY.totalWins))
                .set(WQ_PLAY.net, summed(WQ_PLAY.net))
                .set(WQ_PLAY.totalPlaysExclCancelled, summed(WQ_PLAY.totalPlaysExclCancelled))
                .set(WQ_PLAY.totalWinsExclCancelled, summed(WQ_PLAY.totalWinsExclCancelled))
                .set(WQ_PLAY.netExclCancelled, summed(WQ_PLAY.netExclCancelled))
                .returning(WQ_PLAY.txCountExclCancelled)
                .fetchSingle()
                .getTxCountExclCancelled();
    }

    /**
     * @param playIsNew  the item created its play: +1 playCount
     * @param playIsLive the item is its play's only surviving transaction (new, or emptied by
     *                   cancels): +1 playCountExclCancelled
     * @return the session's productId, productType and currency after the fold.
     */
    private SessionLabel upsertSession(
            Contribution c, OffsetDateTime startedAt, boolean playIsNew, boolean playIsLive) {
        ProjectionItem item = c.item();
        int playDelta = playIsNew ? 1 : 0;
        int playExclDelta = playIsLive ? 1 : 0;
        return dsl.insertInto(WQ_SESSION)
                .set(WQ_SESSION.userId, item.userId())
                .set(WQ_SESSION.batchId, item.batchId())
                .set(WQ_SESSION.startedAt, startedAt)
                .set(WQ_SESSION.endedAt, c.at())
                .set(WQ_SESSION.productId, item.productId())
                .set(WQ_SESSION.productType, item.productType())
                .set(WQ_SESSION.currency, item.currency())
                .set(WQ_SESSION.txCount, 1)
                .set(WQ_SESSION.txCountExclCancelled, 1)
                .set(WQ_SESSION.playCount, playDelta)
                .set(WQ_SESSION.playCountExclCancelled, playExclDelta)
                .set(WQ_SESSION.totalPlays, c.plays())
                .set(WQ_SESSION.totalWins, c.wins())
                .set(WQ_SESSION.net, c.net())
                .set(WQ_SESSION.totalPlaysExclCancelled, c.plays())
                .set(WQ_SESSION.totalWinsExclCancelled, c.wins())
                .set(WQ_SESSION.netExclCancelled, c.net())
                .onConflict(WQ_SESSION.userId, WQ_SESSION.startedAt, WQ_SESSION.batchId)
                .doUpdate()
                .set(WQ_SESSION.endedAt, latest(WQ_SESSION.endedAt))
                .set(WQ_SESSION.productId, least(WQ_SESSION.productId))
                .set(WQ_SESSION.productType, least(WQ_SESSION.productType))
                .set(WQ_SESSION.currency, least(WQ_SESSION.currency))
                .set(WQ_SESSION.txCount, summed(WQ_SESSION.txCount))
                .set(WQ_SESSION.txCountExclCancelled, summed(WQ_SESSION.txCountExclCancelled))
                .set(WQ_SESSION.playCount, summed(WQ_SESSION.playCount))
                .set(WQ_SESSION.playCountExclCancelled, summed(WQ_SESSION.playCountExclCancelled))
                .set(WQ_SESSION.totalPlays, summed(WQ_SESSION.totalPlays))
                .set(WQ_SESSION.totalWins, summed(WQ_SESSION.totalWins))
                .set(WQ_SESSION.net, summed(WQ_SESSION.net))
                .set(WQ_SESSION.totalPlaysExclCancelled, summed(WQ_SESSION.totalPlaysExclCancelled))
                .set(WQ_SESSION.totalWinsExclCancelled, summed(WQ_SESSION.totalWinsExclCancelled))
                .set(WQ_SESSION.netExclCancelled, summed(WQ_SESSION.netExclCancelled))
                .returning(WQ_SESSION.productId, WQ_SESSION.productType, WQ_SESSION.currency)
                .fetchSingle()
                .map(r -> new SessionLabel(r.get(WQ_SESSION.productId),
                        r.get(WQ_SESSION.productType), r.get(WQ_SESSION.currency)));
    }

    /** The session's labels after a fold: each LEAST over its items. */
    private record SessionLabel(String productId, String productType, String currency) {
    }

    /** existing + incoming, for ON CONFLICT DO UPDATE. */
    private static <T extends Number> Field<T> summed(Field<T> field) {
        return field.plus(DSL.excluded(field));
    }

    /** GREATEST(existing, incoming). */
    private static <T> Field<T> latest(Field<T> field) {
        return DSL.greatest(field, DSL.excluded(field));
    }

    /** LEAST(existing, incoming); Postgres LEAST ignores NULLs. */
    private static <T> Field<T> least(Field<T> field) {
        return DSL.least(field, DSL.excluded(field));
    }

    private record Contribution(
            ProjectionItem item, OffsetDateTime at, BigDecimal plays, BigDecimal wins) {

        BigDecimal net() {
            return wins.subtract(plays);
        }
    }

    /**
     * Where the group's row lives after this item. {@code previousStart} is non-null only when
     * this item re-anchored the group; the row is still at {@code previousStart} until moved.
     */
    private record Anchor(boolean isNew, OffsetDateTime startedAt, OffsetDateTime previousStart) {
    }
}
