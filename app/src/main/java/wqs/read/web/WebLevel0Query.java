package wqs.read.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.db.TxTypeRegistry;
import wqs.domain.ViewKind;
import wqs.read.ItemEntry;
import wqs.read.ItemKey;
import wqs.read.Level0Filter;
import wqs.read.Level0Query;
import wqs.read.TypeCodes;

/** Level-0 reads of the web view; touches only "wqWebItem". */
@Component
public class WebLevel0Query implements Level0Query {

    private static final String COUNT = """
            select count(*) from "wqWebItem"
            where %s
            """;

    private static final String KEYS = """
            select "wqWebItem"."occurredAt", "wqWebItem"."itemId" from "wqWebItem"
            where %s
            order by "wqWebItem"."occurredAt" desc, "wqWebItem"."itemId" desc
            offset :offset rows fetch next :limit rows only
            """;

    private static final String FETCH = """
            select "wqWebItem"."userId", "wqWebItem"."itemId", "wqWebItem"."source", "wqWebItem"."sourceId",
                   "wqWebItem"."occurredAt", "wqWebItem"."productId", "wqWebItem"."batchId",
                   "wqWebItem"."subBatchId", "wqWebItem"."type", "wqWebItem"."amount", "wqWebItem"."currency",
                   "wqWebItem"."balanceAfter", "wqWebItem"."cancelled", "wqWebItem"."cancelledBy",
                   "wqWebItem"."cancels", "wqWebItem"."detail", "wqWebItem"."ingestedAt", "wqWebItem"."productType"
            from "wqWebItem"
            join unnest(cast(:ids as bigint[])) as "wanted" ("id") on "wqWebItem"."itemId" = "wanted"."id"
            where ("wqWebItem"."userId" = :userId
                   and "wqWebItem"."occurredAt" between cast(:min as timestamptz) and cast(:max as timestamptz))
            """;

    private final WebItemMapper mapper;
    private final TxTypeRegistry txTypes;

    public WebLevel0Query(WebItemMapper mapper, TxTypeRegistry txTypes) {
        this.mapper = mapper;
        this.txTypes = txTypes;
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }

    @Override
    public long count(JdbcClient tx, Level0Filter f, Instant lo, Instant hi) {
        return range(tx.sql(COUNT.formatted(range(f))), f, lo, hi).query(Long.class).single();
    }

    @Override
    public List<ItemKey> keys(
            JdbcClient tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
        return range(tx.sql(KEYS.formatted(range(f))), f, lo, hi)
                .param("offset", offset)
                .param("limit", limit)
                .query((rs, n) -> new ItemKey(
                        rs.getObject("occurredAt", OffsetDateTime.class).toInstant(), rs.getLong("itemId")))
                .list();
    }

    @Override
    public List<ItemEntry> fetch(JdbcClient tx, long userId, List<ItemKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Instant min = keys.stream().map(ItemKey::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant max = keys.stream().map(ItemKey::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        // One array bind instead of 2 binds per key: page size is unbounded. Joined through
        // unnest(?), which Postgres evaluates once; "itemId" = any(?) re-evaluates the text-bound
        // array per row (array_in is not constant-folded), O(rows x keys). itemId comes from a
        // global sequence, so within the user and the occurredAt window it identifies one row.
        long[] ids = keys.stream().mapToLong(ItemKey::itemId).toArray();
        // Codes resolve through the caller's transaction (M2), never the registry's writer client.
        TypeCodes codes = id -> txTypes.codeOf(tx, id);
        Map<ItemKey, ItemEntry> byKey = new HashMap<>();
        tx.sql(FETCH)
                .param("ids", ids)
                .param("userId", userId)
                .param("min", utc(min))
                .param("max", utc(max))
                .query(rs -> {
                    byKey.put(new ItemKey(rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
                            rs.getLong("itemId")), mapper.toEntry(rs, codes));
                });
        List<ItemEntry> out = new ArrayList<>(keys.size());
        for (ItemKey k : keys) {
            ItemEntry e = byKey.get(k);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The key-range predicate, value-free: the filter only decides which fixed fragments appear,
     * and {@link #range(JdbcClient.StatementSpec, Level0Filter, Instant, Instant)} binds them.
     */
    private static String range(Level0Filter f) {
        StringBuilder c = new StringBuilder("""
                ("wqWebItem"."userId" = :userId and "wqWebItem"."occurredAt" >= cast(:lo as timestamptz) \
                and "wqWebItem"."occurredAt" < cast(:hi as timestamptz)""");
        if (f.excludeCancelled()) {
            c.append(" and not (\"wqWebItem\".\"cancelled\")");
        }
        if (!f.productTypes().isEmpty()) {
            c.append(" and \"wqWebItem\".\"productType\" in (:productTypes)");
        }
        if (!f.typeIds().isEmpty()) {
            c.append(" and \"wqWebItem\".\"type\" in (:typeIds)");
        }
        return c.append(')').toString();
    }

    private static JdbcClient.StatementSpec range(JdbcClient.StatementSpec spec, Level0Filter f, Instant lo, Instant hi) {
        spec = spec.param("userId", f.userId()).param("lo", utc(lo)).param("hi", utc(hi));
        if (!f.productTypes().isEmpty()) {
            spec = spec.param("productTypes", f.productTypes());
        }
        if (!f.typeIds().isEmpty()) {
            spec = spec.param("typeIds", f.typeIds());
        }
        return spec;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
