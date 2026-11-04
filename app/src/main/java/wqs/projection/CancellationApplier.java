package wqs.projection;

import static wqs.projection.Binds.timestamptz;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.CounterSentinels;
import wqs.domain.MoneyDirection;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.TransactionType;
import wqs.domain.ViewKind;

/**
 * Applies a cancellation to an already-projected transaction (spec 6.3), or parks it until the transaction
 * arrives.
 *
 * <p><b>Resolution.</b> A cancel targets a projected transaction of its OWN source: a TRANSACTION cancel cancels
 * a TRANSACTION, a PAYMENT cancel a PAYMENT. The original is therefore resolved through
 * "wqPrjTransactionLocator" by (cancel.source, cancel.cancels) -- the locator's own key -- never by sourceId
 * alone, which different sources may share. The same-source assumption is to be confirmed with
 * the producer (O-13).
 *
 * <p><b>Effects</b>, each the exact inverse of what the original contributed when it arrived:
 * <ul>
 *   <li>Transaction row: "cancelled" = true on the original's one row; the TRANSACTION
 *       excl-cancelled counter loses one in each view its source feeds, at the original's own (occurredAt, productType).
 *       cntAll is untouched -- a cancelled transaction is still returned when the filter is off.</li>
 *   <li>Rollups (sessioned original): txCountExclCancelled -1 and the original's own money
 *       (computed exactly as {@link RollupUpdater} added it) on its play and its session; the
 *       session's playCountExclCancelled -1 when the play's txCountExclCancelled reaches 0.</li>
 *   <li>Page index: a session row is visible while the session has at least one surviving
 *       (non-cancelled, non-cancellation) transaction. A session never mixes sources (only sources
 *       that feed every view are sessioned, {@link RollupUpdater#requireSessionable}), so that is
 *       one check against "wqPrjTransaction" and one "visibleExclCancelled" flag for both
 *       endpoints. If nothing survives, the row flips to hidden and the HIGH_LEVEL excl-cancelled
 *       counter loses one in each view the original's source feeds, at the SESSION's key (start and
 *       productType from the session row, never the original's). An unsessioned original's own row
 *       flips at the transaction's key. A live transaction joining later revives the row
 *       ({@link HighLevelUpdater}).</li>
 * </ul>
 * The session's key is not touched: startedAt and the product fold over every transaction, cancelled or
 * not, so a cancel never moves a row.
 *
 * <p><b>A zero-amount original</b> (spec 4.1, plan D6) is stored like any other but is in no rollup
 * sum and no page unit: the cancel resolves against it -- it never parks -- marks its row cancelled
 * and takes it out of its own ("zeroAmount" = true) TRANSACTION excl-cancelled tally, and nothing
 * else. Nor does a live zero-amount row keep a session visible.
 *
 * <p><b>Owners.</b> Every lookup and update is by the owner ("ownerType", "userId"): a cancel resolves
 * only an original of its own owner, so owner "2-7" can never cancel player "1-7"'s transaction.
 *
 * <p><b>Idempotence.</b> The writer's locator gate stops a replayed cancel before it gets here.
 * A different cancel of an already-cancelled transaction changes no tally; it only lowers
 * "cancelledBy" to the smallest cancel id, so the stored state does not depend on which of two
 * cancels arrived first (P-1).
 *
 * <p>Strict, like the counters: every update here targets a row the original's own arrival
 * created, so a missing row or a negative is a bug and fails the transaction. No clamping.
 */
@Component
public class CancellationApplier {

    private static final short KIND_SESSION = 0;

    private static final String LOCATE = """
            select "userId", "occurredAt" from "wqPrjTransactionLocator"
            where "source" = ? and "sourceId" = ? and "ownerType" = ? and "userId" = ?
            """;
    private static final String FETCH_ORIGINAL = """
            select "ownerType", "userId", "occurredAt", "source", "sourceId", "productType", "batchId",
                   "subBatchId", "type", "amount", "cancelled", "cancels", "zeroAmount"
            from "wqPrjTransaction"
            where "ownerType" = ? and "userId" = ? and "occurredAt" = ? and "source" = ? and "sourceId" = ?
            """;
    private static final String SUBTRACT_FROM_PLAY = """
            update "wqPrjPlay" set
                "txCountExclCancelled" = "wqPrjPlay"."txCountExclCancelled" - ?,
                "totalPlaysExclCancelled" = "wqPrjPlay"."totalPlaysExclCancelled" - ?,
                "totalWinsExclCancelled" = "wqPrjPlay"."totalWinsExclCancelled" - ?,
                "netExclCancelled" = "wqPrjPlay"."netExclCancelled" - ?
            where "ownerType" = ? and "userId" = ? and "batchId" = ? and "subBatchId" = ?
            returning "txCountExclCancelled"
            """;
    private static final String SUBTRACT_FROM_SESSION = """
            update "wqPrjSession" set
                "txCountExclCancelled" = "wqPrjSession"."txCountExclCancelled" - ?,
                "playCountExclCancelled" = "wqPrjSession"."playCountExclCancelled" - ?,
                "totalPlaysExclCancelled" = "wqPrjSession"."totalPlaysExclCancelled" - ?,
                "totalWinsExclCancelled" = "wqPrjSession"."totalWinsExclCancelled" - ?,
                "netExclCancelled" = "wqPrjSession"."netExclCancelled" - ?
            where "ownerType" = ? and "userId" = ? and "batchId" = ?
            returning "ownerType", "userId", "batchId", "startedAt", "endedAt", "productType",
                      "txCountExclCancelled", "playCountExclCancelled"
            """;

    private static final RowMapper<Located> LOCATED = (rs, n) -> new Located(
            rs.getLong("userId"), rs.getObject("occurredAt", OffsetDateTime.class));
    private static final RowMapper<Original> ORIGINAL = (rs, n) -> new Original(
            rs.getShort("ownerType"), rs.getLong("userId"), rs.getObject("occurredAt", OffsetDateTime.class),
            rs.getShort("source"), rs.getString("sourceId"), rs.getString("productType"),
            rs.getString("batchId"), rs.getString("subBatchId"), rs.getString("type"),
            rs.getBigDecimal("amount"), rs.getBoolean("cancelled"), rs.getString("cancels"),
            rs.getBoolean("zeroAmount"));
    private static final RowMapper<Session> SESSION = (rs, n) -> new Session(
            rs.getShort("ownerType"), rs.getLong("userId"), rs.getString("batchId"),
            rs.getObject("startedAt", OffsetDateTime.class),
            rs.getObject("endedAt", OffsetDateTime.class), rs.getString("productType"),
            rs.getInt("txCountExclCancelled"), rs.getInt("playCountExclCancelled"));

    private final JdbcClient jdbc;
    private final CounterUpdater counterUpdater;
    private final PendingCancelStore pendingCancelStore;

    public CancellationApplier(
            JdbcClient jdbc, CounterUpdater counterUpdater, PendingCancelStore pendingCancelStore) {
        this.jdbc = jdbc;
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
    public boolean apply(ProjectionTransaction cancel) {
        if (!cancel.isCancellation()) {
            throw new IllegalArgumentException("not a cancellation: " + cancel.sourceId());
        }
        Located located = locate(cancel);
        if (located == null) {
            pendingCancelStore.park(cancel);
            return false;
        }
        Original original = fetchOriginal(cancel, located);

        SourceKind source = SourceKind.ofCode(original.source());
        if (original.cancelled()) {
            lowerCancelledBy(cancel, original);
            return true;
        }
        markCancelled(cancel, original);

        short ownerType = original.ownerType();
        long userId = original.userId();
        Instant occurredAt = original.occurredAt().toInstant();
        for (ViewKind view : source.views()) {
            counterUpdater.uncountExclCancelled(
                    ownerType, userId, view, PageUnit.TRANSACTION, original.productType(), original.type(),
                    original.zeroAmount(), occurredAt);
        }

        // A cancellation contributed to no rollup and no page unit, so cancelling one (should a
        // producer ever do so) has only the transaction-level effect above. Nor did a zero-amount
        // transaction (plan D6): in no rollup sum, no page unit.
        if (isCancellation(original.cancels()) || original.zeroAmount()) {
            return true;
        }

        if (original.batchId() == null) {
            hide(source, ownerType, userId, occurredAt, (short) source.code(), original.sourceId(),
                    original.productType());
            return true;
        }

        Session session = subtractFromRollups(original);
        if (!sessionSurvives(session)) {
            hide(source, ownerType, userId, session.startedAt().toInstant(), KIND_SESSION,
                    session.batchId(), session.productType());
        }
        return true;
    }

    /**
     * The original's locator row as (userId, occurredAt), or null if not yet seen.
     *
     * The cancel's owner (ownerType, userId) must match the original's: a transaction of another owner is treated as not
     * seen, so the cancel parks (and, since {@link PendingCancelStore#drainFor} matches on userId
     * too, stays parked, surfacing through the oldest-age gauge). A cross-user cancel is an
     * expected mismatch, not a failure; an unexpected failure on the drain path is contained by
     * {@link DrainedCancelApplier}'s savepoint and dead-letters the cancel, never the original.
     */
    private Located locate(ProjectionTransaction cancel) {
        return jdbc.sql(LOCATE)
                .params((short) cancel.source().code(), cancel.cancels(), cancel.ownerType(), cancel.userId())
                .query(LOCATED)
                .optional()
                .orElse(null);
    }

    /** The located original's transaction row. */
    private Original fetchOriginal(ProjectionTransaction cancel, Located located) {
        return jdbc.sql(FETCH_ORIGINAL)
                .params(cancel.ownerType(), located.userId(), timestamptz(located.occurredAt()),
                        (short) cancel.source().code(), cancel.cancels())
                .query(ORIGINAL)
                .single();
    }

    private void markCancelled(ProjectionTransaction cancel, Original original) {
        int rows = jdbc.sql(MARK_CANCELLED)
                .param(cancel.sourceId())
                .params(identifies(original))
                .update();
        requireOne(rows, "transaction row", original);
    }

    private void lowerCancelledBy(ProjectionTransaction cancel, Original original) {
        int rows = jdbc.sql(LOWER_CANCELLED_BY)
                .param(cancel.sourceId())
                .params(identifies(original))
                .update();
        requireOne(rows, "transaction row", original);
    }

    /**
     * Subtracts the original's own contribution from its play and session ExclCancelled columns.
     *
     * @return the session row after the update (its current key and bounds)
     */
    private Session subtractFromRollups(Original original) {
        short ownerType = original.ownerType();
        long userId = original.userId();
        String batchId = original.batchId();
        // Mirrors RollupUpdater exactly: direction by type, magnitude by abs(amount).
        MoneyDirection direction = TransactionType.directionOf(original.type());
        BigDecimal amount = original.amount().abs();
        BigDecimal plays = direction == MoneyDirection.PLAY ? amount : BigDecimal.ZERO;
        BigDecimal wins = direction == MoneyDirection.WIN ? amount : BigDecimal.ZERO;
        BigDecimal net = wins.subtract(plays);

        boolean playEmptied = false;
        if (original.subBatchId() != null) {
            int survivors = jdbc.sql(SUBTRACT_FROM_PLAY)
                    .params(1, plays, wins, net, ownerType, userId, batchId, original.subBatchId())
                    .query(Integer.class)
                    .single();
            requireNonNegative(survivors, "play txCountExclCancelled", original);
            playEmptied = survivors == 0;
        }

        Session session = jdbc.sql(SUBTRACT_FROM_SESSION)
                .params(1, playEmptied ? 1 : 0, plays, wins, net, ownerType, userId, batchId)
                .query(SESSION)
                .single();
        requireNonNegative(session.txCountExclCancelled(), "session txCountExclCancelled", original);
        requireNonNegative(session.playCountExclCancelled(), "session playCountExclCancelled", original);
        return session;
    }

    /**
     * Whether the session keeps a surviving transaction. Every transaction of the session lies in
     * [startedAt, endedAt] (both fold over all of them), so the bound loses nothing and lets the
     * partitioned transaction table prune.
     */
    private boolean sessionSurvives(Session session) {
        return Boolean.TRUE.equals(jdbc.sql(SURVIVOR)
                .params(session.ownerType(), session.userId(), timestamptz(session.startedAt()),
                        timestamptz(session.endedAt()), session.batchId(), "")
                .query(Boolean.class)
                .single());
    }

    /**
     * Hides one page-index row and takes it out of the HIGH_LEVEL excl-cancelled tally of every
     * view {@code source} feeds -- the views the row is counted in. The row was visible -- the
     * original was a surviving transaction until now -- so anything but exactly one flip is a bug.
     */
    private void hide(SourceKind source, short ownerType, long userId, Instant at, short kind, String refId,
                      String productType) {
        int rows = jdbc.sql(HIDE)
                .params(ownerType, userId, timestamptz(at), kind, refId)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("expected to hide exactly one visible page-index row,"
                    + " hid " + rows + ": userId=" + userId + " at=" + at
                    + " kind=" + kind + " refId=" + refId);
        }
        for (ViewKind view : source.views()) {
            counterUpdater.uncountExclCancelled(ownerType, userId, view, PageUnit.HIGH_LEVEL, productType,
                    CounterSentinels.ALL_TYPES, false, at);
        }
    }

    private static boolean isCancellation(String cancels) {
        return cancels != null && !cancels.isBlank();
    }

    private static void requireOne(int rows, String what, Original original) {
        if (rows != 1) {
            throw new IllegalStateException("expected exactly one " + what + ", got " + rows
                    + ": " + describe(original));
        }
    }

    private static void requireNonNegative(int value, String what, Original original) {
        if (value < 0) {
            throw new IllegalStateException(what + " went negative (" + value
                    + ") cancelling " + describe(original));
        }
    }

    private static String describe(Original original) {
        return "ownerType=" + original.ownerType() + " userId=" + original.userId() + " source=" + original.source()
                + " sourceId=" + original.sourceId() + " occurredAt=" + original.occurredAt();
    }

    /** The original's full identity: partition key first, so the update prunes. */
    private static Object[] identifies(Original original) {
        return new Object[] {original.ownerType(), original.userId(), timestamptz(original.occurredAt()), original.source(),
                original.sourceId()};
    }

    /** The original's locator row. */
    private record Located(long userId, OffsetDateTime occurredAt) {
    }

    /** The columns of the original's "wqPrjTransaction" row that a cancel reads. */
    private record Original(
            short ownerType, long userId, OffsetDateTime occurredAt, short source, String sourceId,
            String productType, String batchId, String subBatchId, String type, BigDecimal amount,
            boolean cancelled, String cancels, boolean zeroAmount) {
    }

    /** The session row after a cancel's subtraction: its key, bounds and survivor tallies. */
    private record Session(
            short ownerType, long userId, String batchId, OffsetDateTime startedAt, OffsetDateTime endedAt,
            String productType, int txCountExclCancelled, int playCountExclCancelled) {
    }

    private static final String HIDE = """
            update "wqPrjTimeline" set "visibleExclCancelled" = false
            where "ownerType" = ? and "userId" = ? and "occurredAt" = ? and "kind" = ? and "refId" = ?
              and "visibleExclCancelled" = true
            """;

    private static final String IDENTIFIES = """
            "ownerType" = ? and "userId" = ? and "occurredAt" = ? and "source" = ? and "sourceId" = ?""";
    private static final String MARK_CANCELLED = """
            update "wqPrjTransaction" set "cancelled" = true, "cancelledBy" = ?
            where %s
            """.formatted(IDENTIFIES);
    private static final String LOWER_CANCELLED_BY = """
            update "wqPrjTransaction" set "cancelledBy" = least("wqPrjTransaction"."cancelledBy", ?)
            where %s
            """.formatted(IDENTIFIES);

    private static final String SURVIVOR = """
            select exists (
                select 1 from "wqPrjTransaction"
                where "ownerType" = ? and "userId" = ? and "occurredAt" between ? and ? and "batchId" = ?
                  and "cancelled" = false and not "zeroAmount"
                  and ("cancels" is null or trim("cancels") = ?))
            """;
}
