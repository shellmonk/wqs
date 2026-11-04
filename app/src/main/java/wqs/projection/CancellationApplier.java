package wqs.projection;

import static wqs.projection.Binds.timestamptz;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.MoneyDirection;
import wqs.domain.ProjectionTransaction;
import wqs.domain.TransactionType;

/**
 * Applies a cancellation to an already-projected transaction (spec 6.3), or parks it until the transaction
 * arrives.
 *
 * <p><b>Resolution.</b> A cancel targets a projected transaction of its OWN source: a TRANSACTION cancel cancels
 * a TRANSACTION, a PAYMENT cancel a PAYMENT. The original is therefore resolved by (cancel.source,
 * cancel.cancels) -- the leading columns of "wqPrjTransaction"'s unique key -- never by sourceId alone, which
 * different sources may share. The same-source assumption is to be confirmed with the producer (O-13).
 * The cancel carries no timestamp of its original, so the lookup is bounded to the months the owner has
 * transactions in ({@link OwnerMonths}, plan D3): one statement, one index probe per such month's
 * partition, never a probe of every partition.
 *
 * <p><b>Effects</b>, each the exact inverse of what the original contributed when it arrived:
 * <ul>
 *   <li>Transaction row: "cancelled" = true on the original's one row; its TX excl-cancelled count
 *       in "wqOwnerMonth" loses one, at the original's own (month, source, productType, type). "cnt" is
 *       untouched -- a cancelled transaction is still returned when the filter is off.</li>
 *   <li>A standalone original (no batch id) is its own level-1/2 entry: its ENTRY excl-cancelled
 *       count loses one at its own key.</li>
 *   <li>Rollups (sessioned original): txCountExclCancelled -1 and the original's own money
 *       (computed exactly as {@link RollupUpdater} added it) on its play and its session; the
 *       session's playCountExclCancelled -1 when the play's txCountExclCancelled reaches 0. When the
 *       session's txCountExclCancelled reaches 0 nothing of it survives: its generated
 *       "visibleExclCancelled" turns false and its ENTRY excl-cancelled count loses one, at the
 *       SESSION's key (start, first source, productType from the session row, never the original's).
 *       A live transaction joining later revives it ({@link RollupUpdater}).</li>
 * </ul>
 * The session's key is not touched: startedAt and the product fold over every transaction, cancelled or
 * not, so a cancel never moves an entry.
 *
 * <p><b>A zero-amount original</b> (spec 4.1, plan D6) is stored like any other but is in no rollup
 * sum: the cancel resolves against it -- it never parks -- marks its row cancelled and takes it out
 * of its own ("zeroAmount" = true) TX excl-cancelled tally and, when standalone, of its own
 * zero-amount ENTRY tally; nothing else. Nor does a live zero-amount row keep a session visible.
 *
 * <p><b>Owners.</b> Every lookup and update is by the owner ("ownerType", "userId"): a cancel resolves
 * only an original of its own owner, so owner "2-7" can never cancel player "1-7"'s transaction.
 *
 * <p><b>Idempotence.</b> The writer's insert gate stops a replayed cancel before it gets here.
 * A different cancel of an already-cancelled transaction changes no tally; it only lowers
 * "cancelledBy" to the smallest cancel id, so the stored state does not depend on which of two
 * cancels arrived first (P-1).
 *
 * <p>Strict, like the counters: every update here targets a row the original's own arrival
 * created, so a missing row or a negative is a bug and fails the transaction. No clamping.
 */
@Component
public class CancellationApplier {

    /**
     * The original within the owner's months. The month bounds arrive as two parallel arrays, so the
     * statement is one whatever their number; each month is a lateral probe whose "occurredAt" range lets
     * Postgres prune to that month's partition at run time (the unique key's index there). "offset 0"
     * keeps the probe a per-month subquery: pulled up into a plain join, the planner may instead walk
     * every partition in time order for the outer "order by ... limit" (CancelLookupPruningTest). Should
     * a producer ever repeat a sourceId at another instant, the earliest row is the original, so the
     * choice does not depend on storage.
     */
    static final String LOCATE_ORIGINAL = """
            select "t"."ownerType", "t"."userId", "t"."occurredAt", "t"."source", "t"."sourceId", "t"."productType",
                   "t"."batchId", "t"."subBatchId", "t"."type", "t"."amount", "t"."cancelled", "t"."cancels",
                   "t"."zeroAmount"
            from unnest(cast(? as timestamptz[]), cast(? as timestamptz[])) as "m" ("fromAt", "toAt")
            cross join lateral (
                select "ownerType", "userId", "occurredAt", "source", "sourceId", "productType", "batchId",
                       "subBatchId", "type", "amount", "cancelled", "cancels", "zeroAmount"
                from "wqPrjTransaction"
                where "source" = ? and "sourceId" = ? and "ownerType" = ? and "userId" = ?
                  and "occurredAt" >= "m"."fromAt" and "occurredAt" < "m"."toAt"
                offset 0) as "t"
            order by "t"."occurredAt"
            limit 1
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
            returning "ownerType", "userId", "batchId", "startedAt", "productType", "firstSource",
                      "txCountExclCancelled", "playCountExclCancelled"
            """;

    private static final RowMapper<Original> ORIGINAL = (rs, n) -> new Original(
            rs.getShort("ownerType"), rs.getLong("userId"), rs.getObject("occurredAt", OffsetDateTime.class),
            rs.getShort("source"), rs.getString("sourceId"), rs.getString("productType"),
            rs.getString("batchId"), rs.getString("subBatchId"), rs.getString("type"),
            rs.getBigDecimal("amount"), rs.getBoolean("cancelled"), rs.getString("cancels"),
            rs.getBoolean("zeroAmount"));
    private static final RowMapper<Session> SESSION = (rs, n) -> new Session(
            rs.getShort("ownerType"), rs.getLong("userId"), rs.getString("batchId"),
            rs.getObject("startedAt", OffsetDateTime.class), rs.getString("productType"),
            rs.getShort("firstSource"), rs.getInt("txCountExclCancelled"), rs.getInt("playCountExclCancelled"));

    private final JdbcClient jdbc;
    private final CounterUpdater counterUpdater;
    private final PendingCancelStore pendingCancelStore;
    private final OwnerMonths ownerMonths;

    public CancellationApplier(
            JdbcClient jdbc, CounterUpdater counterUpdater, PendingCancelStore pendingCancelStore,
            OwnerMonths ownerMonths) {
        this.jdbc = jdbc;
        this.counterUpdater = counterUpdater;
        this.pendingCancelStore = pendingCancelStore;
        this.ownerMonths = ownerMonths;
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
        Original original = locate(cancel);
        if (original == null) {
            pendingCancelStore.park(cancel);
            return false;
        }

        if (original.cancelled()) {
            lowerCancelledBy(cancel, original);
            return true;
        }
        markCancelled(cancel, original);

        short ownerType = original.ownerType();
        long userId = original.userId();
        Instant occurredAt = original.occurredAt().toInstant();
        counterUpdater.uncountTransactionExcl(ownerType, userId, occurredAt, original.source(),
                original.productType(), original.type(), original.zeroAmount());

        // A cancellation contributed to no rollup and is no entry, so cancelling one (should a
        // producer ever do so) has only the transaction-level effect above.
        if (isCancellation(original.cancels())) {
            return true;
        }
        if (original.batchId() == null) {
            counterUpdater.uncountEntryExcl(ownerType, userId, occurredAt, original.source(),
                    original.productType(), original.zeroAmount());
            return true;
        }
        // A zero-amount session member is in no rollup sum (plan D6): its "zeroTxCount" counts it,
        // cancelled or not.
        if (original.zeroAmount()) {
            return true;
        }

        Session session = subtractFromRollups(original);
        if (session.txCountExclCancelled() == 0) {
            counterUpdater.uncountEntryExcl(ownerType, userId, session.startedAt().toInstant(),
                    session.firstSource(), session.productType(), false);
        }
        return true;
    }

    /**
     * The original's transaction row, or null if not yet seen.
     *
     * The cancel's owner (ownerType, userId) must match the original's: a transaction of another owner is treated as not
     * seen, so the cancel parks (and, since {@link PendingCancelStore#drainFor} matches on the owner
     * too, stays parked, surfacing through the oldest-age gauge until the pendingCancelResolution job
     * removes it). A cross-user cancel is an expected mismatch, not a failure; an unexpected failure on
     * the drain path is contained by {@link DrainedCancelApplier}'s savepoint and dead-letters the cancel,
     * never the original.
     */
    private Original locate(ProjectionTransaction cancel) {
        List<YearMonth> months = ownerMonths.of(cancel.ownerType(), cancel.userId());
        if (months.isEmpty()) {
            return null;
        }
        String[][] bounds = OwnerMonths.bounds(months);
        return jdbc.sql(LOCATE_ORIGINAL)
                .params(bounds[0], bounds[1], (short) cancel.source().code(), cancel.cancels(), cancel.ownerType(),
                        cancel.userId())
                .query(ORIGINAL)
                .optional()
                .orElse(null);
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

    /** The columns of the original's "wqPrjTransaction" row that a cancel reads. */
    private record Original(
            short ownerType, long userId, OffsetDateTime occurredAt, short source, String sourceId,
            String productType, String batchId, String subBatchId, String type, BigDecimal amount,
            boolean cancelled, String cancels, boolean zeroAmount) {
    }

    /** The session row after a cancel's subtraction: its entry key and survivor tallies. */
    private record Session(
            short ownerType, long userId, String batchId, OffsetDateTime startedAt, String productType,
            short firstSource, int txCountExclCancelled, int playCountExclCancelled) {
    }

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
}
