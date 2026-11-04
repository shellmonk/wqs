package wqs.projection;

import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.MoneyDirection;
import wqs.domain.ProjectionTransaction;
import wqs.domain.TransactionType;
import wqs.domain.ViewKind;

/**
 * Maintains session and play aggregates incrementally (spec 5.5). Only ever called for transactions the
 * locator gate accepted as new and that are not cancellations, so incrementing is safe -- a
 * replayed event never reaches here.
 *
 * "totalPlays" is a monetary amount (the sum wagered), not a count. The count metric is
 * "playCount" on a session and "txCount" on a play.
 *
 * Order independence (spec P-1): every column is a commutative, associative fold over the
 * group's transactions, so any arrival order converges to the same row.
 * <ul>
 *   <li>"startedAt" = earliest occurredAt (LEAST). The rows are keyed by ("userId", "batchId"
 *       [, "subBatchId"]) and not partitioned, so a transaction earlier than the current start
 *       RE-ANCHORS the group in place: "startedAt" moves earlier on the same row (plan D5). Rows
 *       derived from the session's start -- its timeline row and HIGH_LEVEL counters -- follow
 *       through {@link RollupResult#sessionKeyChanged}. Play and session re-anchor independently.</li>
 *   <li>"endedAt" = latest occurredAt (GREATEST).</li>
 *   <li>counts and money = sums. The ExclCancelled columns are the same sums over surviving
 *       projected transactions: {@link CancellationApplier} subtracts a cancelled projected transaction's own contribution, and
 *       "playCountExclCancelled" counts plays with a surviving transaction -- so a live projected transaction
 *       joining a play that cancels had emptied brings it back, not only a new play.</li>
 *   <li>"productId" and "productType" = the FIRST projected transaction's pair (plan D6), first by
 *       ("occurredAt", "sourceId", "source") -- "startedAt" is that transaction's occurredAt, and
 *       "firstSourceId" / "firstSource" hold the rest of its identity as the tie-break for transactions
 *       at the same instant, compared in the database's TEXT order (so "10" sorts before "9":
 *       not numeric, but deterministic, which is all P-1 needs). The pair always belongs to one real transaction (a per-column LEAST could
 *       pair one transaction's id with another's type). A transaction replaces the label when it re-anchors
 *       the group, or ties its startedAt with a lower (sourceId, source); a null pair is a pair
 *       like any other.</li>
 *   <li>"zeroTxCount" = the group's zero-amount rows (plan D6), which are stored but add nothing
 *       else: no count, money, start or label, and they never create a group. A zero-amount row
 *       joining an existing group adds one; a group's first non-zero row counts the zero-amount
 *       rows already stored for it (by the "_batch" index), so any arrival order converges.</li>
 *   <li>Keys are owner keys: ("ownerType", "userId", "batchId"[, "subBatchId"]).</li>
 *   <li>"currency" = the first-arriving transaction's. A session is single-currency: a transaction of another
 *       currency is dead-lettered before it gets here ({@link IngestRules}), so every transaction agrees
 *       and arrival order cannot matter.</li>
 * </ul>
 */
@Component
public class RollupUpdater {

    /** Locks an existing play row; empty when the play does not exist yet. */
    private static final String LOCK_PLAY = """
            select "startedAt" from "wqPrjPlay"
            where "ownerType" = ? and "userId" = ? and "batchId" = ? and "subBatchId" = ?
            for update
            """;
    /** Locks an existing session row and reads the key fields a fold may change. */
    private static final String LOCK_SESSION = """
            select "startedAt", "productId", "productType" from "wqPrjSession"
            where "ownerType" = ? and "userId" = ? and "batchId" = ?
            for update
            """;

    private static final String INSERT_PLAY = """
            insert into "wqPrjPlay" ("ownerType", "userId", "batchId", "subBatchId", "startedAt", "endedAt", "productId",
                    "productType", "currency", "firstSourceId", "firstSource", "txCount",
                    "txCountExclCancelled", "totalPlays", "totalWins", "net", "totalPlaysExclCancelled",
                    "totalWinsExclCancelled", "netExclCancelled", "zeroTxCount")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_SESSION = """
            insert into "wqPrjSession" ("ownerType", "userId", "batchId", "startedAt", "endedAt", "productId", "productType",
                    "currency", "firstSourceId", "firstSource", "txCount", "txCountExclCancelled",
                    "playCount", "playCountExclCancelled", "totalPlays", "totalWins", "net",
                    "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled", "zeroTxCount")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** Creates the group's row; returns nothing when a concurrent writer created it first. */
    private static final String CLAIM_PLAY = INSERT_PLAY + """
            on conflict do nothing
            returning "txCountExclCancelled"
            """;
    private static final String CLAIM_SESSION = INSERT_SESSION + """
            on conflict do nothing
            returning "productId", "productType", "currency"
            """;

    /**
     * The fold into an existing, locked row. {@code %s} is the "first transaction" condition from
     * {@link #isFirst}: an SQL constant chosen in Java, never a value.
     */
    private static final String FOLD_PLAY = INSERT_PLAY + """
            on conflict ("ownerType", "userId", "batchId", "subBatchId") do update set
                "startedAt" = least("wqPrjPlay"."startedAt", excluded."startedAt"),
                "endedAt" = greatest("wqPrjPlay"."endedAt", excluded."endedAt"),
                "productId" = case when %1$s then excluded."productId" else "wqPrjPlay"."productId" end,
                "productType" = case when %1$s then excluded."productType" else "wqPrjPlay"."productType" end,
                "firstSourceId" = case when %1$s then excluded."firstSourceId" else "wqPrjPlay"."firstSourceId" end,
                "firstSource" = case when %1$s then excluded."firstSource" else "wqPrjPlay"."firstSource" end,
                "txCount" = "wqPrjPlay"."txCount" + excluded."txCount",
                "txCountExclCancelled" = "wqPrjPlay"."txCountExclCancelled" + excluded."txCountExclCancelled",
                "totalPlays" = "wqPrjPlay"."totalPlays" + excluded."totalPlays",
                "totalWins" = "wqPrjPlay"."totalWins" + excluded."totalWins",
                "net" = "wqPrjPlay"."net" + excluded."net",
                "totalPlaysExclCancelled" = "wqPrjPlay"."totalPlaysExclCancelled" + excluded."totalPlaysExclCancelled",
                "totalWinsExclCancelled" = "wqPrjPlay"."totalWinsExclCancelled" + excluded."totalWinsExclCancelled",
                "netExclCancelled" = "wqPrjPlay"."netExclCancelled" + excluded."netExclCancelled"
            returning "txCountExclCancelled"
            """;
    private static final String FOLD_SESSION = INSERT_SESSION + """
            on conflict ("ownerType", "userId", "batchId") do update set
                "startedAt" = least("wqPrjSession"."startedAt", excluded."startedAt"),
                "endedAt" = greatest("wqPrjSession"."endedAt", excluded."endedAt"),
                "productId" = case when %1$s then excluded."productId" else "wqPrjSession"."productId" end,
                "productType" = case when %1$s then excluded."productType" else "wqPrjSession"."productType" end,
                "firstSourceId" = case when %1$s then excluded."firstSourceId" else "wqPrjSession"."firstSourceId" end,
                "firstSource" = case when %1$s then excluded."firstSource" else "wqPrjSession"."firstSource" end,
                "txCount" = "wqPrjSession"."txCount" + excluded."txCount",
                "txCountExclCancelled" = "wqPrjSession"."txCountExclCancelled" + excluded."txCountExclCancelled",
                "playCount" = "wqPrjSession"."playCount" + excluded."playCount",
                "playCountExclCancelled" = "wqPrjSession"."playCountExclCancelled" + excluded."playCountExclCancelled",
                "totalPlays" = "wqPrjSession"."totalPlays" + excluded."totalPlays",
                "totalWins" = "wqPrjSession"."totalWins" + excluded."totalWins",
                "net" = "wqPrjSession"."net" + excluded."net",
                "totalPlaysExclCancelled" = "wqPrjSession"."totalPlaysExclCancelled" + excluded."totalPlaysExclCancelled",
                "totalWinsExclCancelled" = "wqPrjSession"."totalWinsExclCancelled" + excluded."totalWinsExclCancelled",
                "netExclCancelled" = "wqPrjSession"."netExclCancelled" + excluded."netExclCancelled"
            returning "productId", "productType", "currency"
            """;

    /**
     * The zero-amount rows already stored for a group that is being created, through the "_batch"
     * index (no time bound: a group's rows may lie in any partition). Cancellations never fold, so
     * a zero-amount cancellation is not counted either. {@code %s}: the play predicate or nothing.
     */
    private static final String ZERO_ROWS = """
            select count(*) from "wqPrjTransaction"
            where "ownerType" = ? and "userId" = ? and "batchId" = ?%s
              and "zeroAmount" and "cancels" is null
            """;
    private static final String PLAY_ZERO = """
            update "wqPrjPlay" set "zeroTxCount" = "wqPrjPlay"."zeroTxCount" + 1
            where "ownerType" = ? and "userId" = ? and "batchId" = ? and "subBatchId" = ?
            """;
    private static final String SESSION_ZERO = """
            update "wqPrjSession" set "zeroTxCount" = "wqPrjSession"."zeroTxCount" + 1
            where "ownerType" = ? and "userId" = ? and "batchId" = ?
            """;

    private static final RowMapper<SessionLabel> SESSION_LABEL = (rs, n) -> new SessionLabel(
            rs.getString("productId"), rs.getString("productType"), rs.getString("currency"));

    private final JdbcClient jdbc;

    public RollupUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Folds one transaction into its play and session rollups.
     *
     * Only called for transactions the locator gate accepted as new (so incrementing is safe) and that
     * are not cancellations. A cancellation returns {@link RollupResult#NONE} without touching any
     * rollup: cancellation effects (the ExclCancelled columns) are applied elsewhere, and folding it in
     * here would count a reversed win as a win.
     *
     * The play and the session must change together with the transaction's own rows, so callers
     * must supply the transaction.
     *
     * Lock order: within one call the play row is locked ({@code for update}, or by its insert)
     * before the session row. A transaction applying several projected transactions of the same
     * session can still take those locks in a different order than a concurrent transaction over
     * the same session (projected transaction A's play, then the session, then projected transaction
     * B's play), which could deadlock. That is safe today because a single sequential consumer
     * applies all projected transactions; any future parallel poller or replay path must partition
     * work by session (or by user) before calling this. The delta poller, which runs concurrently
     * with the stream consumer, relies on retry rather than lock order: a 40P01 on either side rolls
     * that transaction back and is retried (the stream re-applies its batch, the poller's run fails
     * and the next fire re-polls), so both converge (phase 6 ruling I2).
     *
     * @throws IllegalArgumentException a sessioned transaction from a source that does not feed
     *         every view; see {@link #requireSessionable}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RollupResult applyTransaction(ProjectionTransaction transaction) {
        if (!transaction.isSessioned() || transaction.isCancellation()) {
            return RollupResult.NONE;
        }
        if (transaction.isZeroAmount()) {
            countZero(transaction);
            return RollupResult.NONE;
        }
        requireSessionable(transaction);

        MoneyDirection direction = TransactionType.directionOf(transaction.type());
        BigDecimal plays = direction == MoneyDirection.PLAY ? transaction.amount().abs() : BigDecimal.ZERO;
        BigDecimal wins = direction == MoneyDirection.WIN ? transaction.amount().abs() : BigDecimal.ZERO;
        Contribution c = new Contribution(
                transaction, transaction.occurredAt().atOffset(ZoneOffset.UTC), plays, wins);

        boolean playIsNew = false;
        boolean playIsLive = false;
        if (transaction.hasPlay()) {
            Integer survivors = null;
            OffsetDateTime playStart = lockPlay(transaction);
            if (playStart == null) {
                survivors = claimPlay(c, zeroRows(transaction, true));
                playIsNew = survivors != null;
                if (!playIsNew) {
                    playStart = lockPlay(transaction); // a concurrent writer created it first
                }
            }
            if (!playIsNew) {
                survivors = foldPlay(c, playStart);
            }
            // This projected transaction is the play's only surviving transaction: the play is new, or every
            // earlier transaction in it was cancelled (spec 6.3). Either way it rejoins the
            // session's playCountExclCancelled.
            playIsLive = survivors == 1;
        }

        SessionKey before = lockSession(transaction);
        SessionLabel label = null;
        if (before == null) {
            label = claimSession(c, playIsNew, playIsLive, zeroRows(transaction, false));
            if (label == null) {
                before = lockSession(transaction); // a concurrent writer created it first
            }
        }
        boolean sessionIsNew = before == null;
        if (!sessionIsNew) {
            label = foldSession(c, before.startedAt(), playIsNew, playIsLive);
        }

        OffsetDateTime start = sessionIsNew || c.at().isBefore(before.startedAt()) ? c.at() : before.startedAt();
        boolean keyChanged = !sessionIsNew
                && (!start.isEqual(before.startedAt())
                        || !Objects.equals(before.productId(), label.productId())
                        || !Objects.equals(before.productType(), label.productType()));
        return new RollupResult(
                sessionIsNew,
                playIsNew,
                start.toInstant(),
                label.productId(),
                label.productType(),
                label.currency(),
                keyChanged ? before.startedAt().toInstant() : null,
                keyChanged ? before.productType() : null,
                keyChanged);
    }

    /**
     * The sessioned-source rule: only sources that feed EVERY view (TRANSACTION and CLAIMABLE_PRIZE
     * today, the only ones that carry a batch id) may open or join a session. A session therefore
     * never mixes sources, and one "visibleExclCancelled" on its timeline row is right for both
     * endpoints. {@link IngestRules#rejection} rejects such a transaction by name
     * ({@code sessioned-source-not-on-every-view}) before anything of it is written, so this is an
     * assertion: reaching it is a bug, and the non-transient exception dead-letters the transaction.
     */
    static void requireSessionable(ProjectionTransaction transaction) {
        if (!transaction.source().views().containsAll(EnumSet.allOf(ViewKind.class))) {
            throw new IllegalArgumentException("transaction " + transaction.sourceId() + " of source "
                    + transaction.source() + " carries batchId " + transaction.batchId()
                    + ", but only sources that feed every view may be sessioned");
        }
    }

    /**
     * A zero-amount row joins its play and session as "zeroTxCount" only (plan D6); a group that
     * does not exist yet counts it when its first non-zero row creates it ({@link #zeroRows}).
     */
    private void countZero(ProjectionTransaction transaction) {
        if (transaction.hasPlay()) {
            jdbc.sql(PLAY_ZERO)
                    .params(transaction.ownerType(), transaction.userId(), transaction.batchId(), transaction.subBatchId())
                    .update();
        }
        jdbc.sql(SESSION_ZERO)
                .params(transaction.ownerType(), transaction.userId(), transaction.batchId())
                .update();
    }

    /** The zero-amount rows stored so far for the transaction's play ({@code play}) or session. */
    private int zeroRows(ProjectionTransaction transaction, boolean play) {
        var spec = jdbc.sql(ZERO_ROWS.formatted(play ? " and \"subBatchId\" = ?" : ""))
                .params(transaction.ownerType(), transaction.userId(), transaction.batchId());
        if (play) {
            spec = spec.param(transaction.subBatchId());
        }
        return spec.query(Integer.class).single();
    }

    /** The play's startedAt, its row locked for the rest of the transaction; null if no play yet. */
    private OffsetDateTime lockPlay(ProjectionTransaction transaction) {
        return jdbc.sql(LOCK_PLAY)
                .params(transaction.ownerType(), transaction.userId(), transaction.batchId(), transaction.subBatchId())
                .query((rs, n) -> rs.getObject("startedAt", OffsetDateTime.class))
                .optional()
                .orElse(null);
    }

    /** The session's key fields before this transaction, its row locked; null if no session yet. */
    private SessionKey lockSession(ProjectionTransaction transaction) {
        return jdbc.sql(LOCK_SESSION)
                .params(transaction.ownerType(), transaction.userId(), transaction.batchId())
                .query((rs, n) -> new SessionKey(rs.getObject("startedAt", OffsetDateTime.class),
                        rs.getString("productId"), rs.getString("productType")))
                .optional()
                .orElse(null);
    }

    /** @return the new play's txCountExclCancelled (1), or null when the play already existed. */
    private Integer claimPlay(Contribution c, int zeroTxCount) {
        return jdbc.sql(CLAIM_PLAY).params(playValues(c, zeroTxCount)).query(Integer.class).optional().orElse(null);
    }

    /** @return the play's txCountExclCancelled after the fold. */
    private int foldPlay(Contribution c, OffsetDateTime startedAt) {
        return jdbc.sql(FOLD_PLAY.formatted(isFirst(c, startedAt, "\"wqPrjPlay\"")))
                .params(playValues(c, 0)) // "zeroTxCount" is not folded
                .query(Integer.class)
                .single();
    }

    private static Object[] playValues(Contribution c, int zeroTxCount) {
        ProjectionTransaction transaction = c.transaction();
        return new Object[] {transaction.ownerType(), transaction.userId(), transaction.batchId(), transaction.subBatchId(),
                timestamptz(c.at()), timestamptz(c.at()), text(transaction.productId()),
                text(transaction.productType()), transaction.currency(), transaction.sourceId(),
                (short) transaction.source().code(), 1, 1, c.plays(), c.wins(), c.net(), c.plays(),
                c.wins(), c.net(), zeroTxCount};
    }

    /** @return the new session's labels, or null when the session already existed. */
    private SessionLabel claimSession(Contribution c, boolean playIsNew, boolean playIsLive, int zeroTxCount) {
        return jdbc.sql(CLAIM_SESSION)
                .params(sessionValues(c, playIsNew, playIsLive, zeroTxCount))
                .query(SESSION_LABEL)
                .optional()
                .orElse(null);
    }

    /**
     * @param playIsNew  the projected transaction created its play: +1 playCount
     * @param playIsLive the projected transaction is its play's only surviving transaction (new, or emptied by
     *                   cancels): +1 playCountExclCancelled
     * @return the session's productId, productType and currency after the fold.
     */
    private SessionLabel foldSession(
            Contribution c, OffsetDateTime startedAt, boolean playIsNew, boolean playIsLive) {
        return jdbc.sql(FOLD_SESSION.formatted(isFirst(c, startedAt, "\"wqPrjSession\"")))
                .params(sessionValues(c, playIsNew, playIsLive, 0)) // "zeroTxCount" is not folded
                .query(SESSION_LABEL)
                .single();
    }

    private static Object[] sessionValues(Contribution c, boolean playIsNew, boolean playIsLive, int zeroTxCount) {
        ProjectionTransaction transaction = c.transaction();
        return new Object[] {transaction.ownerType(), transaction.userId(), transaction.batchId(), timestamptz(c.at()),
                timestamptz(c.at()), text(transaction.productId()), text(transaction.productType()),
                transaction.currency(), transaction.sourceId(), (short) transaction.source().code(), 1, 1,
                playIsNew ? 1 : 0, playIsLive ? 1 : 0, c.plays(), c.wins(), c.net(), c.plays(), c.wins(),
                c.net(), zeroTxCount};
    }

    /** The session's labels after a fold: the first transaction's product pair, and the currency. */
    private record SessionLabel(String productId, String productType, String currency) {
    }

    /** An existing session's start and product before this transaction is folded in. */
    private record SessionKey(OffsetDateTime startedAt, String productId, String productType) {
    }

    /**
     * Whether the incoming transaction becomes the group's first transaction, for ON CONFLICT DO
     * UPDATE, as an SQL condition over {@code table}. {@code startedAt} is the locked row's start,
     * MIN(occurredAt) so far: a transaction earlier than it re-anchors the group and is strictly
     * earliest; one later is not first; one AT it ties, and the lower (sourceId, source) wins --
     * the only comparison left to the database.
     */
    private static String isFirst(Contribution c, OffsetDateTime startedAt, String table) {
        if (c.at().isBefore(startedAt)) {
            return "true";
        }
        if (c.at().isAfter(startedAt)) {
            return "false";
        }
        return "(excluded.\"firstSourceId\", excluded.\"firstSource\") < (" + table
                + ".\"firstSourceId\", " + table + ".\"firstSource\")";
    }

    private record Contribution(
            ProjectionTransaction transaction, OffsetDateTime at, BigDecimal plays, BigDecimal wins) {

        BigDecimal net() {
            return wins.subtract(plays);
        }
    }
}
