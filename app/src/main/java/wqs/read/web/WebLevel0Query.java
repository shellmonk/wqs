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
import wqs.domain.ViewKind;
import wqs.read.TransactionEntry;
import wqs.read.TransactionKey;
import wqs.read.Level0Filter;
import wqs.read.Level0Query;

/** Level-0 reads of the web view; touches only "wqPrjWebTransaction". */
@Component
public class WebLevel0Query implements Level0Query {

    private static final String COUNT = """
            select count(*) from "wqPrjWebTransaction"
            where %s
            """;

    private static final String KEYS = """
            select "wqPrjWebTransaction"."occurredAt", "wqPrjWebTransaction"."transactionId" from "wqPrjWebTransaction"
            where %s
            order by "wqPrjWebTransaction"."occurredAt" desc, "wqPrjWebTransaction"."transactionId" desc
            offset :offset rows fetch next :limit rows only
            """;

    private static final String FETCH = """
            select "wqPrjWebTransaction"."userId", "wqPrjWebTransaction"."transactionId", "wqPrjWebTransaction"."source", "wqPrjWebTransaction"."sourceId",
                   "wqPrjWebTransaction"."occurredAt", "wqPrjWebTransaction"."productId", "wqPrjWebTransaction"."batchId",
                   "wqPrjWebTransaction"."subBatchId", "wqPrjWebTransaction"."type", "wqPrjWebTransaction"."amount", "wqPrjWebTransaction"."currency",
                   "wqPrjWebTransaction"."balanceAfter", "wqPrjWebTransaction"."cancelled", "wqPrjWebTransaction"."cancelledBy",
                   "wqPrjWebTransaction"."cancels", "wqPrjWebTransaction"."detail", "wqPrjWebTransaction"."ingestedAt", "wqPrjWebTransaction"."productType"
            from "wqPrjWebTransaction"
            join unnest(cast(:ids as bigint[])) as "wanted" ("id") on "wqPrjWebTransaction"."transactionId" = "wanted"."id"
            where ("wqPrjWebTransaction"."userId" = :userId
                   and "wqPrjWebTransaction"."occurredAt" between cast(:min as timestamptz) and cast(:max as timestamptz))
            """;

    private final WebTransactionMapper mapper;

    public WebLevel0Query(WebTransactionMapper mapper) {
        this.mapper = mapper;
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
    public List<TransactionKey> keys(
            JdbcClient tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
        return range(tx.sql(KEYS.formatted(range(f))), f, lo, hi)
                .param("offset", offset)
                .param("limit", limit)
                .query((rs, n) -> new TransactionKey(
                        rs.getObject("occurredAt", OffsetDateTime.class).toInstant(), rs.getLong("transactionId")))
                .list();
    }

    @Override
    public List<TransactionEntry> fetch(JdbcClient tx, long userId, List<TransactionKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Instant min = keys.stream().map(TransactionKey::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant max = keys.stream().map(TransactionKey::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        // One array bind instead of 2 binds per key: page size is unbounded. Joined through
        // unnest(?), which Postgres evaluates once; "transactionId" = any(?) re-evaluates the text-bound
        // array per row (array_in is not constant-folded), O(rows x keys). transactionId comes from a
        // global sequence, so within the user and the occurredAt window it identifies one row.
        long[] ids = keys.stream().mapToLong(TransactionKey::transactionId).toArray();
        Map<TransactionKey, TransactionEntry> byKey = new HashMap<>();
        tx.sql(FETCH)
                .param("ids", ids)
                .param("userId", userId)
                .param("min", utc(min))
                .param("max", utc(max))
                .query(rs -> {
                    byKey.put(new TransactionKey(rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
                            rs.getLong("transactionId")), mapper.toEntry(rs));
                });
        List<TransactionEntry> out = new ArrayList<>(keys.size());
        for (TransactionKey k : keys) {
            TransactionEntry e = byKey.get(k);
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
                ("wqPrjWebTransaction"."userId" = :userId and "wqPrjWebTransaction"."occurredAt" >= cast(:lo as timestamptz) \
                and "wqPrjWebTransaction"."occurredAt" < cast(:hi as timestamptz)""");
        if (f.excludeCancelled()) {
            c.append(" and not (\"wqPrjWebTransaction\".\"cancelled\")");
        }
        if (!f.productTypes().isEmpty()) {
            c.append(" and \"wqPrjWebTransaction\".\"productType\" in (:productTypes)");
        }
        if (!f.typeCodes().isEmpty()) {
            c.append(" and \"wqPrjWebTransaction\".\"type\" in (:typeCodes)");
        }
        return c.append(')').toString();
    }

    private static JdbcClient.StatementSpec range(JdbcClient.StatementSpec spec, Level0Filter f, Instant lo, Instant hi) {
        spec = spec.param("userId", f.userId()).param("lo", utc(lo)).param("hi", utc(hi));
        if (!f.productTypes().isEmpty()) {
            spec = spec.param("productTypes", f.productTypes());
        }
        if (!f.typeCodes().isEmpty()) {
            spec = spec.param("typeCodes", f.typeCodes());
        }
        return spec;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
