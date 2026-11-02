package wqs.read;

import static wqs.db.generated.Tables.WQ_PLAY;
import static wqs.db.generated.Tables.WQ_SESSION;

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
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import wqs.db.generated.tables.records.WqPlayRecord;
import wqs.db.generated.tables.records.WqSessionRecord;
import wqs.domain.AggregationLevel;

/**
 * Level-1/2 page assembly (spec 7.3, 7.4): turns a page's high-level keys into entries, in the
 * keys' (page-index) order. At most four queries whatever the page size and whatever the type
 * registry's cache holds -- the page's sessions, their plays (level 2 only), the view's items of
 * those sessions, and its unsessioned items -- each an array-bound join on the page's keys, so none
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
 * shared by both views; the view's {@link HighLevelItemQuery} reads its own item table.
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
            DSLContext tx, HighLevelItemQuery items, long userId, Nesting n, List<HighLevelKey> keys) {
        List<HighLevelKey> sessionKeys = new ArrayList<>();
        List<HighLevelKey> itemKeys = new ArrayList<>();
        for (HighLevelKey k : keys) {
            (k.kind() == 0 ? sessionKeys : itemKeys).add(k);
        }

        Map<HighLevelKey, WqSessionRecord> sessions = sessions(tx, userId, sessionKeys);
        Map<String, List<WqPlayRecord>> plays = Map.of();
        Map<String, List<TxView>> txsByBatch = Map.of();
        Map<String, Map<String, List<TxView>>> txsByPlay = Map.of();
        if (!sessions.isEmpty()) {
            // Bounds of the page's sessions' "startedAt" only (partition pruning of "wqSession");
            // every member probe is per session, inside its own window (final review F1).
            Instant lo = sessions.values().stream().map(s -> s.getStartedAt().toInstant())
                    .min(Comparator.naturalOrder()).orElseThrow();
            Instant hi = sessions.values().stream().map(s -> s.getStartedAt().toInstant())
                    .max(Comparator.naturalOrder()).orElseThrow();
            Map<String, WqSessionRecord> byBatch = new HashMap<>();
            sessions.values().forEach(s -> byBatch.put(s.getBatchId(), s));
            if (n.level() == AggregationLevel.L2) {
                plays = plays(tx, userId, byBatch, lo, hi, n.excludeCancelled());
            }
            Map<String, Set<String>> listedPlays = new HashMap<>();
            plays.forEach((b, ps) -> ps.forEach(
                    p -> listedPlays.computeIfAbsent(b, x -> new HashSet<>()).add(p.getSubBatchId())));
            List<ItemEntry> children = items.sessionItems(
                    tx, userId, byBatch.keySet(), lo, hi, n.to(), n.transactionTypes(), n.excludeCancelled());
            txsByBatch = new HashMap<>();
            txsByPlay = new HashMap<>();
            for (ItemEntry e : children) {
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
        Map<HighLevelKey, ItemEntry> unsessioned = itemKeys.isEmpty() ? Map.of() : items.unsessioned(tx, userId, itemKeys);

        List<HighLevelEntry> out = new ArrayList<>(keys.size());
        for (HighLevelKey k : keys) {
            if (k.kind() == 0) {
                WqSessionRecord s = sessions.get(k);
                if (s != null) {
                    out.add(session(s, n, txsByBatch.get(s.getBatchId()),
                            plays.getOrDefault(s.getBatchId(), List.of()),
                            txsByPlay.getOrDefault(s.getBatchId(), Map.of())));
                }
            } else {
                ItemEntry e = unsessioned.get(k);
                if (e != null) {
                    out.add(TxView.of(e));
                }
            }
        }
        return out;
    }

    private static SessionView session(WqSessionRecord s, Nesting n, List<TxView> txs,
                                       List<WqPlayRecord> plays, Map<String, List<TxView>> txsByPlay) {
        boolean excl = n.excludeCancelled();
        List<PlayView> subitems = null;
        List<TxView> sessionTxs = txs == null ? List.of() : txs;
        if (n.level() == AggregationLevel.L2) {
            subitems = new ArrayList<>(plays.size());
            for (WqPlayRecord p : plays) {
                subitems.add(new PlayView(p.getSubBatchId(), p.getStartedAt().toInstant(),
                        excl ? p.getTotalPlaysExclCancelled() : p.getTotalPlays(),
                        excl ? p.getTotalWinsExclCancelled() : p.getTotalWins(),
                        excl ? p.getNetExclCancelled() : p.getNet(),
                        txsByPlay.getOrDefault(p.getSubBatchId(), List.of())));
            }
            sessionTxs = txs; // play-less transactions only; null (omitted) when none
        }
        return new SessionView(SessionView.KIND, s.getBatchId(), s.getStartedAt().toInstant(),
                s.getEndedAt().toInstant(), s.getProductId(), s.getProductType(), s.getCurrency(),
                excl ? s.getTotalPlaysExclCancelled() : s.getTotalPlays(),
                excl ? s.getTotalWinsExclCancelled() : s.getTotalWins(),
                excl ? s.getNetExclCancelled() : s.getNet(),
                excl ? s.getPlayCountExclCancelled() : s.getPlayCount(),
                sessionTxs, subitems);
    }

    /** The page's sessions by key: one query, the batch ids array-bound, startedAt range-pruned. */
    private static Map<HighLevelKey, WqSessionRecord> sessions(DSLContext tx, long userId, List<HighLevelKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Instant lo = keys.stream().map(HighLevelKey::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant hi = keys.stream().map(HighLevelKey::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        Set<HighLevelKey> wanted = Set.copyOf(keys);
        Table<?> ids = batchIds(keys.stream().map(HighLevelKey::refId).collect(LinkedHashSet::new, Set::add, Set::addAll));
        Map<HighLevelKey, WqSessionRecord> out = new HashMap<>();
        tx.select(WQ_SESSION.fields())
                .from(WQ_SESSION)
                .join(ids).on(WQ_SESSION.batchId.eq(ids.field("id", String.class)))
                .where(WQ_SESSION.userId.eq(userId))
                .and(WQ_SESSION.startedAt.between(utc(lo), utc(hi)))
                .fetchInto(WQ_SESSION)
                .forEach(r -> {
                    HighLevelKey k = new HighLevelKey(r.getStartedAt().toInstant(), (short) 0, r.getBatchId());
                    if (wanted.contains(k)) {
                        out.put(k, r);
                    }
                });
        return out;
    }

    /**
     * The sessions' plays, newest first ("startedAt" desc, "subBatchId" desc), grouped by batch:
     * per session, through "wqPlay_batch", inside the session's own window (final review F1).
     * Under excludeCancelled a play with no surviving transaction is omitted.
     */
    private static Map<String, List<WqPlayRecord>> plays(DSLContext tx, long userId,
            Map<String, WqSessionRecord> sessions, Instant lo, Instant hi, boolean excludeCancelled) {
        Table<?> ids = batchIds(sessions.keySet());
        Map<String, List<WqPlayRecord>> out = new LinkedHashMap<>();
        tx.select(WQ_PLAY.fields())
                .from(ids)
                .join(WQ_SESSION).on(WQ_SESSION.userId.eq(userId)
                        .and(WQ_SESSION.batchId.eq(ids.field("id", String.class)))
                        .and(WQ_SESSION.startedAt.between(utc(lo), utc(hi))))
                .join(WQ_PLAY).on(WQ_PLAY.userId.eq(userId)
                        .and(WQ_PLAY.batchId.eq(WQ_SESSION.batchId))
                        .and(WQ_PLAY.startedAt.between(WQ_SESSION.startedAt, WQ_SESSION.endedAt)))
                .where(excludeCancelled ? WQ_PLAY.txCountExclCancelled.gt(0) : DSL.noCondition())
                .orderBy(WQ_PLAY.startedAt.desc(), WQ_PLAY.subBatchId.desc())
                .fetchInto(WQ_PLAY)
                .forEach(p -> {
                    if (sessions.containsKey(p.getBatchId())) {
                        out.computeIfAbsent(p.getBatchId(), b -> new ArrayList<>()).add(p);
                    }
                });
        return out;
    }

    /** One array bind, joined through unnest(?) as the phase-4 item fetch does. */
    private static Table<?> batchIds(Set<String> ids) {
        return DSL.unnest(DSL.val(ids.toArray(String[]::new), SQLDataType.CLOB.getArrayDataType())).as("wanted", "id");
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
