package wqs.projection;

import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.MoneyDirection;
import wqs.domain.ProjectionTransaction;
import wqs.domain.TransactionType;

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
 *   <li>"startedAt" = earliest occurredAt. It is the partition key and part of the primary key,
 *       so a transaction earlier than the current anchor RE-ANCHORS the group: the row is deleted,
 *       re-inserted at the new startedAt with its aggregates intact, the transaction is then folded in
 *       by the ordinary upsert, and the locator is pointed at the new startedAt. Play and session
 *       re-anchor independently.</li>
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
 *   <li>"currency" = the first-arriving transaction's. A session is single-currency: a transaction of another
 *       currency is dead-lettered before it gets here ({@link IngestRules}), so every transaction agrees
 *       and arrival order cannot matter.</li>
 * </ul>
 */
@Component
public class RollupUpdater {

    /**
     * The "subBatchId" of a session's own locator row. Unreachable from input: ProjectionTransaction
     * normalises a blank subBatchId to null, and a transaction without a play never claims a play row.
     */
    private static final String SESSION_MARKER = "";

    private static final String CLAIM = """
            insert into "wqPrjGroupLocator" ("userId", "batchId", "subBatchId", "startedAt")
            values (?, ?, ?, ?)
            on conflict do nothing
            """;
    private static final String LOCK_ANCHOR = """
            select "startedAt" from "wqPrjGroupLocator"
            where "userId" = ? and "batchId" = ? and "subBatchId" = ?
            for update
            """;
    private static final String MOVE_ANCHOR = """
            update "wqPrjGroupLocator" set "startedAt" = ?
            where "userId" = ? and "batchId" = ? and "subBatchId" = ?
            """;
    private static final String SESSION_PRODUCT = """
            select "productId", "productType" from "wqPrjSession"
            where "userId" = ? and "startedAt" = ? and "batchId" = ?
            """;

    /** Every column of "wqPrjPlay" / "wqPrjSession", so a re-keyed row is re-inserted whole. */
    private static final String PLAY_COLUMNS = """
            "userId", "batchId", "subBatchId", "startedAt", "endedAt", "productId", "currency", \
            "txCount", "txCountExclCancelled", "totalPlays", "totalWins", "net", \
            "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled", "productType", \
            "firstSourceId", "firstSource"\
            """;
    private static final String SESSION_COLUMNS = """
            "userId", "batchId", "startedAt", "endedAt", "productId", "currency", "txCount", \
            "txCountExclCancelled", "playCount", "playCountExclCancelled", "totalPlays", "totalWins", \
            "net", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled", \
            "productType", "firstSourceId", "firstSource"\
            """;
    private static final String DELETE_PLAY = """
            delete from "wqPrjPlay"
            where "userId" = ? and "startedAt" = ? and "batchId" = ? and "subBatchId" = ?
            returning %s
            """.formatted(PLAY_COLUMNS);
    private static final String DELETE_SESSION = """
            delete from "wqPrjSession"
            where "userId" = ? and "startedAt" = ? and "batchId" = ?
            returning %s
            """.formatted(SESSION_COLUMNS);
    private static final String REINSERT_PLAY = """
            insert into "wqPrjPlay" (%s) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(PLAY_COLUMNS);
    private static final String REINSERT_SESSION = """
            insert into "wqPrjSession" (%s) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(SESSION_COLUMNS);

    /**
     * The fold. {@code %1$s} is the table, {@code %2$s} the "first transaction" condition from
     * {@link #isFirst}: an SQL constant chosen in Java, never a value.
     */
    private static final String UPSERT_PLAY = """
            insert into %1$s ("userId", "batchId", "subBatchId", "startedAt", "endedAt", "productId",
                    "productType", "currency", "firstSourceId", "firstSource", "txCount",
                    "txCountExclCancelled", "totalPlays", "totalWins", "net", "totalPlaysExclCancelled",
                    "totalWinsExclCancelled", "netExclCancelled")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict ("userId", "startedAt", "batchId", "subBatchId") do update set
                "endedAt" = greatest(%1$s."endedAt", excluded."endedAt"),
                "productId" = case when %2$s then excluded."productId" else %1$s."productId" end,
                "productType" = case when %2$s then excluded."productType" else %1$s."productType" end,
                "firstSourceId" = case when %2$s then excluded."firstSourceId" else %1$s."firstSourceId" end,
                "firstSource" = case when %2$s then excluded."firstSource" else %1$s."firstSource" end,
                "txCount" = %1$s."txCount" + excluded."txCount",
                "txCountExclCancelled" = %1$s."txCountExclCancelled" + excluded."txCountExclCancelled",
                "totalPlays" = %1$s."totalPlays" + excluded."totalPlays",
                "totalWins" = %1$s."totalWins" + excluded."totalWins",
                "net" = %1$s."net" + excluded."net",
                "totalPlaysExclCancelled" = %1$s."totalPlaysExclCancelled" + excluded."totalPlaysExclCancelled",
                "totalWinsExclCancelled" = %1$s."totalWinsExclCancelled" + excluded."totalWinsExclCancelled",
                "netExclCancelled" = %1$s."netExclCancelled" + excluded."netExclCancelled"
            returning "txCountExclCancelled"
            """;
    private static final String UPSERT_SESSION = """
            insert into %1$s ("userId", "batchId", "startedAt", "endedAt", "productId", "productType",
                    "currency", "firstSourceId", "firstSource", "txCount", "txCountExclCancelled",
                    "playCount", "playCountExclCancelled", "totalPlays", "totalWins", "net",
                    "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict ("userId", "startedAt", "batchId") do update set
                "endedAt" = greatest(%1$s."endedAt", excluded."endedAt"),
                "productId" = case when %2$s then excluded."productId" else %1$s."productId" end,
                "productType" = case when %2$s then excluded."productType" else %1$s."productType" end,
                "firstSourceId" = case when %2$s then excluded."firstSourceId" else %1$s."firstSourceId" end,
                "firstSource" = case when %2$s then excluded."firstSource" else %1$s."firstSource" end,
                "txCount" = %1$s."txCount" + excluded."txCount",
                "txCountExclCancelled" = %1$s."txCountExclCancelled" + excluded."txCountExclCancelled",
                "playCount" = %1$s."playCount" + excluded."playCount",
                "playCountExclCancelled" = %1$s."playCountExclCancelled" + excluded."playCountExclCancelled",
                "totalPlays" = %1$s."totalPlays" + excluded."totalPlays",
                "totalWins" = %1$s."totalWins" + excluded."totalWins",
                "net" = %1$s."net" + excluded."net",
                "totalPlaysExclCancelled" = %1$s."totalPlaysExclCancelled" + excluded."totalPlaysExclCancelled",
                "totalWinsExclCancelled" = %1$s."totalWinsExclCancelled" + excluded."totalWinsExclCancelled",
                "netExclCancelled" = %1$s."netExclCancelled" + excluded."netExclCancelled"
            returning "productId", "productType", "currency"
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
     * A re-anchor is a delete plus an insert plus a locator update; committing only part of it
     * would lose or duplicate a group, so callers must supply the transaction.
     *
     * Lock order: within one call the play's locator row is locked before the session's. A
     * transaction applying several projected transactions of the same session can still take those locks in a
     * different order than a concurrent transaction over the same session (projected transaction A's play, then
     * the session, then projected transaction B's play), which could deadlock. That is safe today because a single
     * sequential consumer applies all projected transactions; any future parallel poller or replay path must
     * partition work by session (or by user) before calling this. The delta poller, which runs
     * concurrently with the stream consumer, relies on retry rather than lock order: a 40P01 on
     * either side rolls that transaction back and is retried (the stream re-applies its batch, the
     * poller's run fails and the next fire re-polls), so both converge (phase 6 ruling I2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RollupResult applyTransaction(ProjectionTransaction transaction) {
        if (!transaction.isSessioned() || transaction.isCancellation()) {
            return RollupResult.NONE;
        }

        MoneyDirection direction = TransactionType.directionOf(transaction.type());
        BigDecimal plays = direction == MoneyDirection.PLAY ? transaction.amount().abs() : BigDecimal.ZERO;
        BigDecimal wins = direction == MoneyDirection.WIN ? transaction.amount().abs() : BigDecimal.ZERO;
        Contribution contribution = new Contribution(
                transaction, transaction.occurredAt().atOffset(ZoneOffset.UTC), plays, wins);

        boolean playIsNew = false;
        boolean playIsLive = false;
        if (transaction.hasPlay()) {
            Anchor play = claim(transaction.userId(), transaction.batchId(), transaction.subBatchId(), contribution.at());
            if (play.previousStart() != null) {
                movePlay(transaction, play.previousStart(), play.startedAt());
            }
            int survivors = upsertPlay(contribution, play);
            playIsNew = play.isNew();
            // This projected transaction is the play's only surviving transaction: the play is new, or every
            // earlier transaction in it was cancelled (spec 6.3). Either way it rejoins the
            // session's playCountExclCancelled.
            playIsLive = survivors == 1;
        }

        Anchor session = claim(transaction.userId(), transaction.batchId(), SESSION_MARKER, contribution.at());
        OffsetDateTime oldStart = session.previousStart() != null
                ? session.previousStart()
                : session.startedAt();
        Product oldProduct = session.isNew() ? null : sessionProduct(transaction, oldStart);
        if (session.previousStart() != null) {
            moveSession(transaction, session.previousStart(), session.startedAt());
        }
        SessionLabel label = upsertSession(contribution, session, playIsNew, playIsLive);

        boolean keyChanged = !session.isNew()
                && (session.previousStart() != null
                        || !Objects.equals(oldProduct.productId(), label.productId())
                        || !Objects.equals(oldProduct.productType(), label.productType()));
        return new RollupResult(
                session.isNew(),
                playIsNew,
                session.startedAt().toInstant(),
                label.productId(),
                label.productType(),
                label.currency(),
                keyChanged ? oldStart.toInstant() : null,
                keyChanged ? oldProduct.productType() : null,
                keyChanged);
    }

    /** The session's (productId, productType) before this transaction is folded in. */
    private Product sessionProduct(ProjectionTransaction transaction, OffsetDateTime startedAt) {
        return jdbc.sql(SESSION_PRODUCT)
                .params(transaction.userId(), timestamptz(startedAt), transaction.batchId())
                .query((rs, n) -> new Product(rs.getString("productId"), rs.getString("productType")))
                .single();
    }

    private record Product(String productId, String productType) {
    }

    /**
     * Finds or creates the group's locator row and moves it earlier if this projected transaction predates it.
     * The locator row is locked for the rest of the transaction.
     */
    private Anchor claim(long userId, String batchId, String subBatchId, OffsetDateTime at) {
        int created = jdbc.sql(CLAIM)
                .params(userId, batchId, subBatchId, timestamptz(at))
                .update();
        if (created == 1) {
            return new Anchor(true, at, null);
        }

        OffsetDateTime current = jdbc.sql(LOCK_ANCHOR)
                .params(userId, batchId, subBatchId)
                .query((rs, n) -> rs.getObject("startedAt", OffsetDateTime.class))
                .single();
        if (!at.isBefore(current)) {
            return new Anchor(false, current, null);
        }

        jdbc.sql(MOVE_ANCHOR)
                .params(timestamptz(at), userId, batchId, subBatchId)
                .update();
        return new Anchor(false, at, current);
    }

    /** Re-keys the play row to an earlier startedAt, possibly into another partition. */
    private void movePlay(ProjectionTransaction transaction, OffsetDateTime from, OffsetDateTime to) {
        List<SqlParameterValue> row = jdbc.sql(DELETE_PLAY)
                .params(transaction.userId(), timestamptz(from), transaction.batchId(), transaction.subBatchId())
                .query(RollupUpdater::wholeRow)
                .single();
        jdbc.sql(REINSERT_PLAY).params(rekeyed(row, 3, to)).update();
    }

    /** Re-keys the session row to an earlier startedAt, possibly into another partition. */
    private void moveSession(ProjectionTransaction transaction, OffsetDateTime from, OffsetDateTime to) {
        List<SqlParameterValue> row = jdbc.sql(DELETE_SESSION)
                .params(transaction.userId(), timestamptz(from), transaction.batchId())
                .query(RollupUpdater::wholeRow)
                .single();
        jdbc.sql(REINSERT_SESSION).params(rekeyed(row, 2, to)).update();
    }

    /**
     * A deleted row's columns as typed binds, in RETURNING order: timestamptz read and bound as
     * {@link OffsetDateTime}, every other column with its own JDBC type, so nulls and numeric
     * scale survive the re-insert unchanged.
     */
    private static List<SqlParameterValue> wholeRow(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<SqlParameterValue> values = new ArrayList<>(md.getColumnCount());
        for (int i = 1; i <= md.getColumnCount(); i++) {
            values.add("timestamptz".equals(md.getColumnTypeName(i))
                    ? timestamptz(rs.getObject(i, OffsetDateTime.class))
                    : new SqlParameterValue(md.getColumnType(i), rs.getObject(i)));
        }
        return values;
    }

    /** The row with its "startedAt" (at {@code startedAtIndex}, 0-based) replaced. */
    private static List<SqlParameterValue> rekeyed(
            List<SqlParameterValue> row, int startedAtIndex, OffsetDateTime to) {
        List<SqlParameterValue> copy = new ArrayList<>(row);
        copy.set(startedAtIndex, timestamptz(to));
        return copy;
    }

    /** @return the play's txCountExclCancelled after the fold. */
    private int upsertPlay(Contribution c, Anchor anchor) {
        ProjectionTransaction transaction = c.transaction();
        return jdbc.sql(UPSERT_PLAY.formatted("\"wqPrjPlay\"", isFirst(c, anchor, "\"wqPrjPlay\"")))
                .params(transaction.userId(), transaction.batchId(), transaction.subBatchId(), timestamptz(anchor.startedAt()),
                        timestamptz(c.at()), text(transaction.productId()), text(transaction.productType()),
                        transaction.currency(), transaction.sourceId(), (short) transaction.source().code(),
                        1, 1, c.plays(), c.wins(), c.net(), c.plays(), c.wins(), c.net())
                .query(Integer.class)
                .single();
    }

    /**
     * @param playIsNew  the projected transaction created its play: +1 playCount
     * @param playIsLive the projected transaction is its play's only surviving transaction (new, or emptied by
     *                   cancels): +1 playCountExclCancelled
     * @return the session's productId, productType and currency after the fold.
     */
    private SessionLabel upsertSession(
            Contribution c, Anchor anchor, boolean playIsNew, boolean playIsLive) {
        ProjectionTransaction transaction = c.transaction();
        int playDelta = playIsNew ? 1 : 0;
        int playExclDelta = playIsLive ? 1 : 0;
        return jdbc.sql(UPSERT_SESSION.formatted("\"wqPrjSession\"", isFirst(c, anchor, "\"wqPrjSession\"")))
                .params(transaction.userId(), transaction.batchId(), timestamptz(anchor.startedAt()), timestamptz(c.at()),
                        text(transaction.productId()), text(transaction.productType()), transaction.currency(),
                        transaction.sourceId(), (short) transaction.source().code(), 1, 1, playDelta, playExclDelta,
                        c.plays(), c.wins(), c.net(), c.plays(), c.wins(), c.net())
                .query(SESSION_LABEL)
                .single();
    }

    /** The session's labels after a fold: the first transaction's product pair, and the currency. */
    private record SessionLabel(String productId, String productType, String currency) {
    }

    /**
     * Whether the incoming transaction becomes the group's first transaction, for ON CONFLICT DO UPDATE, as an
     * SQL condition over {@code table}. The claim has already moved the anchor to
     * MIN(occurredAt), so: a transaction that re-anchored the group is strictly earliest; one later than
     * the anchor is not first; one AT the anchor ties, and the lower (sourceId, source) wins --
     * the only comparison left to the database.
     */
    private static String isFirst(Contribution c, Anchor anchor, String table) {
        if (anchor.previousStart() != null) {
            return "true";
        }
        if (!c.at().isEqual(anchor.startedAt())) {
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

    /**
     * Where the group's row lives after this transaction. {@code previousStart} is non-null only when
     * this transaction re-anchored the group; the row is still at {@code previousStart} until moved.
     */
    private record Anchor(boolean isNew, OffsetDateTime startedAt, OffsetDateTime previousStart) {
    }
}
