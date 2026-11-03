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
 *   <li>Transaction rows: "cancelled" = true in every view the original's source feeds; the TRANSACTION
 *       excl-cancelled counter loses one in each, at the original's own (occurredAt, productType).
 *       cntAll is untouched -- a cancelled transaction is still returned when the filter is off.</li>
 *   <li>Rollups (sessioned original): txCountExclCancelled -1 and the original's own money
 *       (computed exactly as {@link RollupUpdater} added it) on its play and its session; the
 *       session's playCountExclCancelled -1 when the play's txCountExclCancelled reaches 0.</li>
 *   <li>Page index, PER VIEW: a session row in view V is visible while the session has at least
 *       one surviving (non-cancelled, non-cancellation) transaction whose source feeds V. For each view
 *       the original's source feeds, that is re-evaluated against the transaction table; if nothing
 *       survives, the row flips to hidden and the HIGH_LEVEL excl-cancelled counter loses one at
 *       the SESSION's key (start and productType from the session row, never the original's). An
 *       unsessioned original's own row flips at the transaction's key. A live transaction joining later
 *       revives the row ({@link HighLevelUpdater}).</li>
 * </ul>
 * The session's key is not touched: startedAt and the product fold over every transaction, cancelled or
 * not, so a cancel never moves a row.
 *
 * <p><b>A zero-amount original</b> (spec 4.1) has a locator row and nothing else: the cancel
 * resolves against it -- it never parks -- and marks nothing.
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
    private static final String SESSION_MARKER = "";

    private static final String LOCATE = """
            select "userId", "occurredAt", "zeroAmount" from "wqPrjTransactionLocator"
            where "source" = ? and "sourceId" = ? and "userId" = ?
            """;
    private static final String FETCH_ORIGINAL = """
            select "userId", "occurredAt", "source", "sourceId", "productType", "batchId",
                   "subBatchId", "type", "amount", "cancelled", "cancels"
            from "wqPrjCcTransaction"
            where "userId" = ? and "occurredAt" = ? and "source" = ? and "sourceId" = ?
            """;
    private static final String SUBTRACT_FROM_PLAY = """
            update "wqPrjPlay" set
                "txCountExclCancelled" = "wqPrjPlay"."txCountExclCancelled" - ?,
                "totalPlaysExclCancelled" = "wqPrjPlay"."totalPlaysExclCancelled" - ?,
                "totalWinsExclCancelled" = "wqPrjPlay"."totalWinsExclCancelled" - ?,
                "netExclCancelled" = "wqPrjPlay"."netExclCancelled" - ?
            where "userId" = ? and "startedAt" = ? and "batchId" = ? and "subBatchId" = ?
            returning "txCountExclCancelled"
            """;
    private static final String SUBTRACT_FROM_SESSION = """
            update "wqPrjSession" set
                "txCountExclCancelled" = "wqPrjSession"."txCountExclCancelled" - ?,
                "playCountExclCancelled" = "wqPrjSession"."playCountExclCancelled" - ?,
                "totalPlaysExclCancelled" = "wqPrjSession"."totalPlaysExclCancelled" - ?,
                "totalWinsExclCancelled" = "wqPrjSession"."totalWinsExclCancelled" - ?,
                "netExclCancelled" = "wqPrjSession"."netExclCancelled" - ?
            where "userId" = ? and "startedAt" = ? and "batchId" = ?
            returning "userId", "batchId", "startedAt", "endedAt", "productType",
                      "txCountExclCancelled", "playCountExclCancelled"
            """;
    private static final String GROUP_START = """
            select "startedAt" from "wqPrjGroupLocator"
            where "userId" = ? and "batchId" = ? and "subBatchId" = ?
            """;

    private static final RowMapper<Located> LOCATED = (rs, n) -> new Located(
            rs.getLong("userId"), rs.getObject("occurredAt", OffsetDateTime.class),
            rs.getBoolean("zeroAmount"));
    private static final RowMapper<Original> ORIGINAL = (rs, n) -> new Original(
            rs.getLong("userId"), rs.getObject("occurredAt", OffsetDateTime.class),
            rs.getShort("source"), rs.getString("sourceId"), rs.getString("productType"),
            rs.getString("batchId"), rs.getString("subBatchId"), rs.getString("type"),
            rs.getBigDecimal("amount"), rs.getBoolean("cancelled"), rs.getString("cancels"));
    private static final RowMapper<Session> SESSION = (rs, n) -> new Session(
            rs.getLong("userId"), rs.getString("batchId"),
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
        if (located.zeroAmount()) {
            // A zero-amount original is in no view, rollup or counter (spec 4.1): the cancel
            // resolves -- it must not park -- and there is nothing to mark.
            return true;
        }
        Original original = fetchOriginal(cancel, located);

        SourceKind source = SourceKind.ofCode(original.source());
        if (original.cancelled()) {
            lowerCancelledBy(cancel, original, source);
            return true;
        }
        markCancelled(cancel, original, source);

        long userId = original.userId();
        Instant occurredAt = original.occurredAt().toInstant();
        for (ViewKind view : source.views()) {
            counterUpdater.uncountExclCancelled(
                    userId, view, PageUnit.TRANSACTION, original.productType(), original.type(),
                    occurredAt);
        }

        // A cancellation contributed to no rollup and no page unit, so cancelling one (should a
        // producer ever do so) has only the transaction-level effect above.
        if (isCancellation(original.cancels())) {
            return true;
        }

        if (original.batchId() == null) {
            for (ViewKind view : source.views()) {
                hide(view, userId, occurredAt, (short) source.code(), original.sourceId(),
                        original.productType());
            }
            return true;
        }

        Session session = subtractFromRollups(original);
        for (ViewKind view : source.views()) {
            if (!sessionSurvivesIn(view, session)) {
                hide(view, userId, session.startedAt().toInstant(), KIND_SESSION,
                        session.batchId(), session.productType());
            }
        }
        return true;
    }

    /**
     * The original's locator row as (userId, occurredAt, zeroAmount), or null if not yet seen.
     *
     * The cancel's userId must match the original's: a transaction of another user is treated as not
     * seen, so the cancel parks (and, since {@link PendingCancelStore#drainFor} matches on userId
     * too, stays parked, surfacing through the oldest-age gauge). A cross-user cancel is an
     * expected mismatch, not a failure; an unexpected failure on the drain path is contained by
     * {@link DrainedCancelApplier}'s savepoint and dead-letters the cancel, never the original.
     */
    private Located locate(ProjectionTransaction cancel) {
        return jdbc.sql(LOCATE)
                .params((short) cancel.source().code(), cancel.cancels(), cancel.userId())
                .query(LOCATED)
                .optional()
                .orElse(null);
    }

    /** The located original, read from "wqPrjCcTransaction" -- every source feeds /cc. */
    private Original fetchOriginal(ProjectionTransaction cancel, Located located) {
        return jdbc.sql(FETCH_ORIGINAL)
                .params(located.userId(), timestamptz(located.occurredAt()),
                        (short) cancel.source().code(), cancel.cancels())
                .query(ORIGINAL)
                .single();
    }

    private void markCancelled(ProjectionTransaction cancel, Original original, SourceKind source) {
        for (ViewKind view : source.views()) {
            TransactionTable t = TransactionTable.of(view);
            int rows = jdbc.sql(t.markCancelled())
                    .param(cancel.sourceId())
                    .params(identifies(original))
                    .update();
            requireOne(rows, "transaction row in " + view, original);
        }
    }

    private void lowerCancelledBy(ProjectionTransaction cancel, Original original, SourceKind source) {
        for (ViewKind view : source.views()) {
            TransactionTable t = TransactionTable.of(view);
            int rows = jdbc.sql(t.lowerCancelledBy())
                    .param(cancel.sourceId())
                    .params(identifies(original))
                    .update();
            requireOne(rows, "transaction row in " + view, original);
        }
    }

    /**
     * Subtracts the original's own contribution from its play and session ExclCancelled columns.
     *
     * @return the session row after the update (its current key and bounds)
     */
    private Session subtractFromRollups(Original original) {
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
            String subBatchId = original.subBatchId();
            OffsetDateTime playStart = groupStart(userId, batchId, subBatchId);
            int survivors = jdbc.sql(SUBTRACT_FROM_PLAY)
                    .params(1, plays, wins, net,
                            userId, timestamptz(playStart), batchId, subBatchId)
                    .query(Integer.class)
                    .single();
            requireNonNegative(survivors, "play txCountExclCancelled", original);
            playEmptied = survivors == 0;
        }

        OffsetDateTime sessionStart = groupStart(userId, batchId, SESSION_MARKER);
        Session session = jdbc.sql(SUBTRACT_FROM_SESSION)
                .params(1, playEmptied ? 1 : 0, plays, wins, net,
                        userId, timestamptz(sessionStart), batchId)
                .query(SESSION)
                .single();
        requireNonNegative(session.txCountExclCancelled(), "session txCountExclCancelled", original);
        requireNonNegative(session.playCountExclCancelled(), "session playCountExclCancelled", original);
        return session;
    }

    private OffsetDateTime groupStart(long userId, String batchId, String subBatchId) {
        return jdbc.sql(GROUP_START)
                .params(userId, batchId, subBatchId)
                .query((rs, n) -> rs.getObject("startedAt", OffsetDateTime.class))
                .single();
    }

    /**
     * Whether the session keeps a surviving transaction in {@code view}. Every transaction of the session lies
     * in [startedAt, endedAt] (both fold over all of them), so the bound loses nothing and lets
     * the partitioned transaction table prune.
     */
    private boolean sessionSurvivesIn(ViewKind view, Session session) {
        return Boolean.TRUE.equals(jdbc.sql(TransactionTable.of(view).survivor())
                .params(session.userId(), timestamptz(session.startedAt()),
                        timestamptz(session.endedAt()), session.batchId(), "")
                .query(Boolean.class)
                .single());
    }

    /**
     * Hides one page-index row and takes it out of the HIGH_LEVEL excl-cancelled tally. The row
     * was visible -- the original was a surviving transaction in this view until now -- so anything but
     * exactly one flip is a bug.
     */
    private void hide(ViewKind view, long userId, Instant at, short kind, String refId,
                      String productType) {
        int rows = jdbc.sql(view == ViewKind.WEB ? HIDE_WEB : HIDE_CC)
                .params(userId, timestamptz(at), kind, refId)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("expected to hide exactly one visible page-index row,"
                    + " hid " + rows + ": view=" + view + " userId=" + userId + " at=" + at
                    + " kind=" + kind + " refId=" + refId);
        }
        counterUpdater.uncountExclCancelled(userId, view, PageUnit.HIGH_LEVEL, productType,
                CounterSentinels.ALL_TYPES, at);
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
        return "userId=" + original.userId() + " source=" + original.source()
                + " sourceId=" + original.sourceId() + " occurredAt=" + original.occurredAt();
    }

    /** The original's full identity: partition key first, so the update prunes. */
    private static Object[] identifies(Original original) {
        return new Object[] {original.userId(), timestamptz(original.occurredAt()), original.source(),
                original.sourceId()};
    }

    /** The original's locator row. */
    private record Located(long userId, OffsetDateTime occurredAt, boolean zeroAmount) {
    }

    /** The columns of the original's "wqPrjCcTransaction" row that a cancel reads. */
    private record Original(
            long userId, OffsetDateTime occurredAt, short source, String sourceId,
            String productType, String batchId, String subBatchId, String type, BigDecimal amount,
            boolean cancelled, String cancels) {
    }

    /** The session row after a cancel's subtraction: its key, bounds and survivor tallies. */
    private record Session(
            long userId, String batchId, OffsetDateTime startedAt, OffsetDateTime endedAt,
            String productType, int txCountExclCancelled, int playCountExclCancelled) {
    }

    private static final String HIDE = """
            update "%s" set "visibleExclCancelled" = false
            where "userId" = ? and "occurredAt" = ? and "kind" = ? and "refId" = ?
              and "visibleExclCancelled" = true
            """;
    private static final String HIDE_WEB = HIDE.formatted("wqPrjWebHighLevel");
    private static final String HIDE_CC = HIDE.formatted("wqPrjCcHighLevel");

    /** The two transaction tables share a column shape; this carries one table's statements. */
    private record TransactionTable(String markCancelled, String lowerCancelledBy, String survivor) {

        private static final String IDENTIFIES = """
                "userId" = ? and "occurredAt" = ? and "source" = ? and "sourceId" = ?""";

        static final TransactionTable WEB = of("wqPrjWebTransaction");
        static final TransactionTable CC = of("wqPrjCcTransaction");

        static TransactionTable of(ViewKind view) {
            return view == ViewKind.WEB ? WEB : CC;
        }

        private static TransactionTable of(String table) {
            return new TransactionTable(
                    """
                    update "%s" set "cancelled" = true, "cancelledBy" = ?
                    where %s
                    """.formatted(table, IDENTIFIES),
                    """
                    update "%1$s" set "cancelledBy" = least("%1$s"."cancelledBy", ?)
                    where %2$s
                    """.formatted(table, IDENTIFIES),
                    """
                    select exists (
                        select 1 from "%s"
                        where "userId" = ? and "occurredAt" between ? and ? and "batchId" = ?
                          and "cancelled" = false and ("cancels" is null or trim("cancels") = ?))
                    """.formatted(table));
        }
    }
}
