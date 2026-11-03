package wqs.read;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.domain.AggregationLevel;

/**
 * Level-1/2 page assembly (spec 7.3, 7.4): turns a page's high-level keys into entries, in the
 * keys' (page-index) order. At most four queries whatever the page size -- the page's sessions, their plays (level 2 only), the view's transactions of
 * those sessions, and its unsessioned transactions -- each an array-bound join on the page's keys, so none
 * grows in binds or round trips with the page; each member probe is per session or per key, so
 * none reads rows outside the page's own (final review F1). A session's rows include its
 * cancellation rows dated after its "endedAt", up to the range end (F3); at level 2 a row whose
 * play is not listed stays on the session (F2).
 *
 * <p>Session and play figures are the stored rollups, the excl-cancelled variant when {@code
 * excludeCancelled} (plan D11); nothing is summed on read. {@code transactionTypes} filters only the
 * nested transactions (spec 4.1 (b)): sessions, plays and their totals do not depend on it.
 * {@code excludeCancelled} hides cancelled nested transactions exactly as level 0 does (cancellation
 * rows stay), and at level 2 omits plays with no surviving transaction. The rollup tables are
 * shared by both views; the view's {@link HighLevelTransactionQuery} reads its own projected transaction table.
 */
@Component
public class HighLevelAssembler {

    /** What the nested transactions are filtered by; the page index has been filtered already. */
    public record Nesting(AggregationLevel level, Set<String> transactionTypes, boolean excludeCancelled, Instant to) {

        public Nesting {
            if (level == null || level == AggregationLevel.L0) {
                throw new IllegalArgumentException("assembly is for level 1 or 2, was " + level);
            }
            if (to == null) {
                throw new IllegalArgumentException("the range end is required");
            }
            transactionTypes = Set.copyOf(transactionTypes);
        }
    }

    /**
     * The page's entries, in {@code keys} order, read through {@code tx} (the reader transaction
     * that walked the keys). A key whose row vanished cannot occur inside one snapshot; it is
     * skipped rather than failing the page.
     */
    public List<HighLevelEntry> assemble(
            JdbcClient tx, HighLevelTransactionQuery transactions, long userId, Nesting n, List<HighLevelKey> keys) {
        List<HighLevelKey> sessionKeys = new ArrayList<>();
        List<HighLevelKey> transactionKeys = new ArrayList<>();
        for (HighLevelKey k : keys) {
            (k.kind() == 0 ? sessionKeys : transactionKeys).add(k);
        }

        Map<HighLevelKey, SessionRow> sessions = sessions(tx, userId, sessionKeys);
        Map<String, List<PlayRow>> plays = Map.of();
        Map<String, List<TxView>> txsByBatch = Map.of();
        Map<String, Map<String, List<TxView>>> txsByPlay = Map.of();
        if (!sessions.isEmpty()) {
            // Bounds of the page's sessions' "startedAt" only (partition pruning of "wqPrjSession");
            // every member probe is per session, inside its own window (final review F1).
            Instant lo = sessions.values().stream().map(s -> s.startedAt())
                    .min(Comparator.naturalOrder()).orElseThrow();
            Instant hi = sessions.values().stream().map(s -> s.startedAt())
                    .max(Comparator.naturalOrder()).orElseThrow();
            Map<String, SessionRow> byBatch = new HashMap<>();
            sessions.values().forEach(s -> byBatch.put(s.batchId(), s));
            if (n.level() == AggregationLevel.L2) {
                plays = plays(tx, userId, byBatch, lo, hi, n.excludeCancelled());
            }
            Map<String, Set<String>> listedPlays = new HashMap<>();
            plays.forEach((b, ps) -> ps.forEach(
                    p -> listedPlays.computeIfAbsent(b, x -> new HashSet<>()).add(p.subBatchId())));
            List<TransactionEntry> children = transactions.sessionTransactions(
                    tx, userId, byBatch.keySet(), lo, hi, n.to(), n.transactionTypes(), n.excludeCancelled());
            txsByBatch = new HashMap<>();
            txsByPlay = new HashMap<>();
            for (TransactionEntry e : children) {
                if (!byBatch.containsKey(e.batchId())) {
                    continue;
                }
                // A row whose play is not listed (omitted under excl, or no play row at all) stays
                // on the session rather than vanishing at level 2 (final review F2).
                if (n.level() == AggregationLevel.L2 && e.subBatchId() != null
                        && listedPlays.getOrDefault(e.batchId(), Set.of()).contains(e.subBatchId())) {
                    txsByPlay.computeIfAbsent(e.batchId(), b -> new HashMap<>())
                            .computeIfAbsent(e.subBatchId(), p -> new ArrayList<>()).add(TxView.of(e));
                } else {
                    txsByBatch.computeIfAbsent(e.batchId(), b -> new ArrayList<>()).add(TxView.of(e));
                }
            }
        }
        Map<HighLevelKey, TransactionEntry> unsessioned = transactionKeys.isEmpty() ? Map.of() : transactions.unsessioned(tx, userId, transactionKeys);

        List<HighLevelEntry> out = new ArrayList<>(keys.size());
        for (HighLevelKey k : keys) {
            if (k.kind() == 0) {
                SessionRow s = sessions.get(k);
                if (s != null) {
                    out.add(session(s, n, txsByBatch.get(s.batchId()),
                            plays.getOrDefault(s.batchId(), List.of()),
                            txsByPlay.getOrDefault(s.batchId(), Map.of())));
                }
            } else {
                TransactionEntry e = unsessioned.get(k);
                if (e != null) {
                    out.add(TxView.of(e));
                }
            }
        }
        return out;
    }

    private static SessionView session(SessionRow s, Nesting n, List<TxView> txs,
                                       List<PlayRow> plays, Map<String, List<TxView>> txsByPlay) {
        boolean excl = n.excludeCancelled();
        List<PlayView> subitems = null;
        List<TxView> sessionTxs = txs == null ? List.of() : txs;
        if (n.level() == AggregationLevel.L2) {
            subitems = new ArrayList<>(plays.size());
            for (PlayRow p : plays) {
                subitems.add(new PlayView(p.subBatchId(), p.startedAt(),
                        excl ? p.totalPlaysExclCancelled() : p.totalPlays(),
                        excl ? p.totalWinsExclCancelled() : p.totalWins(),
                        excl ? p.netExclCancelled() : p.net(),
                        txsByPlay.getOrDefault(p.subBatchId(), List.of())));
            }
            sessionTxs = txs; // play-less transactions only; null (omitted) when none
        }
        return new SessionView(SessionView.KIND, s.batchId(), s.startedAt(),
                s.endedAt(), s.productId(), s.productType(), s.currency(),
                excl ? s.totalPlaysExclCancelled() : s.totalPlays(),
                excl ? s.totalWinsExclCancelled() : s.totalWins(),
                excl ? s.netExclCancelled() : s.net(),
                excl ? s.playCountExclCancelled() : s.playCount(),
                sessionTxs, subitems);
    }

    /** The page's sessions by key: one query, the batch ids array-bound, startedAt range-pruned. */
    private static Map<HighLevelKey, SessionRow> sessions(JdbcClient tx, long userId, List<HighLevelKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Instant lo = keys.stream().map(HighLevelKey::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant hi = keys.stream().map(HighLevelKey::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        Set<HighLevelKey> wanted = Set.copyOf(keys);
        Map<HighLevelKey, SessionRow> out = new HashMap<>();
        tx.sql(SESSIONS)
                .param("ids", batchIds(keys.stream().map(HighLevelKey::refId)
                        .collect(LinkedHashSet::new, Set::add, Set::addAll)))
                .param("userId", userId)
                .param("lo", utc(lo))
                .param("hi", utc(hi))
                .query(HighLevelAssembler::sessionRow)
                .list()
                .forEach(r -> {
                    HighLevelKey k = new HighLevelKey(r.startedAt(), (short) 0, r.batchId());
                    if (wanted.contains(k)) {
                        out.put(k, r);
                    }
                });
        return out;
    }

    /**
     * The sessions' plays, newest first ("startedAt" desc, "subBatchId" desc), grouped by batch:
     * per session, through "wqPrjPlay_batch", inside the session's own window (final review F1).
     * Under excludeCancelled a play with no surviving transaction is omitted.
     */
    private static Map<String, List<PlayRow>> plays(JdbcClient tx, long userId,
            Map<String, SessionRow> sessions, Instant lo, Instant hi, boolean excludeCancelled) {
        Map<String, List<PlayRow>> out = new LinkedHashMap<>();
        JdbcClient.StatementSpec spec = tx.sql(excludeCancelled ? PLAYS_EXCL : PLAYS)
                .param("ids", batchIds(sessions.keySet()))
                .param("userId", userId)
                .param("lo", utc(lo))
                .param("hi", utc(hi));
        if (excludeCancelled) {
            spec = spec.param("zero", 0);
        }
        spec.query(HighLevelAssembler::playRow)
                .list()
                .forEach(p -> {
                    if (sessions.containsKey(p.batchId())) {
                        out.computeIfAbsent(p.batchId(), b -> new ArrayList<>()).add(p);
                    }
                });
        return out;
    }

    // Value-free statements: every value is a named bind.
    private static final String SESSIONS = """
            select "wqPrjSession"."userId", "wqPrjSession"."batchId", "wqPrjSession"."startedAt", "wqPrjSession"."endedAt",
                   "wqPrjSession"."productId", "wqPrjSession"."currency", "wqPrjSession"."txCount",
                   "wqPrjSession"."txCountExclCancelled", "wqPrjSession"."playCount", "wqPrjSession"."playCountExclCancelled",
                   "wqPrjSession"."totalPlays", "wqPrjSession"."totalWins", "wqPrjSession"."net",
                   "wqPrjSession"."totalPlaysExclCancelled", "wqPrjSession"."totalWinsExclCancelled",
                   "wqPrjSession"."netExclCancelled", "wqPrjSession"."productType", "wqPrjSession"."firstSourceId",
                   "wqPrjSession"."firstSource"
            from "wqPrjSession"
            join unnest(cast(:ids as text[])) as "wanted" ("id") on "wqPrjSession"."batchId" = "wanted"."id"
            where ("wqPrjSession"."userId" = :userId
                   and "wqPrjSession"."startedAt" between cast(:lo as timestamptz) and cast(:hi as timestamptz))
            """;

    private static final String PLAYS_FROM = """
            select "wqPrjPlay"."userId", "wqPrjPlay"."batchId", "wqPrjPlay"."subBatchId", "wqPrjPlay"."startedAt",
                   "wqPrjPlay"."endedAt", "wqPrjPlay"."productId", "wqPrjPlay"."currency", "wqPrjPlay"."txCount",
                   "wqPrjPlay"."txCountExclCancelled", "wqPrjPlay"."totalPlays", "wqPrjPlay"."totalWins", "wqPrjPlay"."net",
                   "wqPrjPlay"."totalPlaysExclCancelled", "wqPrjPlay"."totalWinsExclCancelled", "wqPrjPlay"."netExclCancelled",
                   "wqPrjPlay"."productType", "wqPrjPlay"."firstSourceId", "wqPrjPlay"."firstSource"
            from unnest(cast(:ids as text[])) as "wanted" ("id")
            join "wqPrjSession" on ("wqPrjSession"."userId" = :userId and "wqPrjSession"."batchId" = "wanted"."id"
                   and "wqPrjSession"."startedAt" between cast(:lo as timestamptz) and cast(:hi as timestamptz))
            join "wqPrjPlay" on ("wqPrjPlay"."userId" = :userId and "wqPrjPlay"."batchId" = "wqPrjSession"."batchId"
                   and "wqPrjPlay"."startedAt" between "wqPrjSession"."startedAt" and "wqPrjSession"."endedAt")
            """;
    private static final String PLAYS_ORDER = """
            order by "wqPrjPlay"."startedAt" desc, "wqPrjPlay"."subBatchId" desc
            """;
    private static final String PLAYS = PLAYS_FROM + PLAYS_ORDER;
    private static final String PLAYS_EXCL = PLAYS_FROM + """
            where "wqPrjPlay"."txCountExclCancelled" > :zero
            """ + PLAYS_ORDER;

    /** The "wqPrjSession" columns assembly reads. */
    private record SessionRow(String batchId, Instant startedAt, Instant endedAt, String productId,
                              String productType, String currency, BigDecimal totalPlays, BigDecimal totalWins,
                              BigDecimal net, BigDecimal totalPlaysExclCancelled, BigDecimal totalWinsExclCancelled,
                              BigDecimal netExclCancelled, int playCount, int playCountExclCancelled) {}

    /** The "wqPrjPlay" columns assembly reads. */
    private record PlayRow(String batchId, String subBatchId, Instant startedAt, BigDecimal totalPlays,
                           BigDecimal totalWins, BigDecimal net, BigDecimal totalPlaysExclCancelled,
                           BigDecimal totalWinsExclCancelled, BigDecimal netExclCancelled) {}

    private static SessionRow sessionRow(ResultSet rs, int n) throws SQLException {
        return new SessionRow(rs.getString("batchId"), instant(rs, "startedAt"), instant(rs, "endedAt"),
                rs.getString("productId"), rs.getString("productType"), rs.getString("currency"),
                rs.getBigDecimal("totalPlays"), rs.getBigDecimal("totalWins"), rs.getBigDecimal("net"),
                rs.getBigDecimal("totalPlaysExclCancelled"), rs.getBigDecimal("totalWinsExclCancelled"),
                rs.getBigDecimal("netExclCancelled"), rs.getInt("playCount"), rs.getInt("playCountExclCancelled"));
    }

    private static PlayRow playRow(ResultSet rs, int n) throws SQLException {
        return new PlayRow(rs.getString("batchId"), rs.getString("subBatchId"), instant(rs, "startedAt"),
                rs.getBigDecimal("totalPlays"), rs.getBigDecimal("totalWins"), rs.getBigDecimal("net"),
                rs.getBigDecimal("totalPlaysExclCancelled"), rs.getBigDecimal("totalWinsExclCancelled"),
                rs.getBigDecimal("netExclCancelled"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** One array bind, joined through unnest(?) as the phase-4 transaction fetch does. */
    private static String[] batchIds(Set<String> ids) {
        return ids.toArray(String[]::new);
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
