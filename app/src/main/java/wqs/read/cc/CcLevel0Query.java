package wqs.read.cc;

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
import wqs.read.ReadPolicy;

/** Level-0 reads of the cc view; reads only the "wqPrjCcTransaction" view (every source). */
@Component
public class CcLevel0Query implements Level0Query {

    /** What /cc shows (plan D1), bound into every statement below. */
    private static final ReadPolicy POLICY = ReadPolicy.CC;

    private static final String COUNT = """
            select count(*) from "wqPrjCcTransaction"
            where %s
            """;

    private static final String KEYS = """
            select "wqPrjCcTransaction"."occurredAt", "wqPrjCcTransaction"."transactionId" from "wqPrjCcTransaction"
            where %s
            order by "wqPrjCcTransaction"."occurredAt" desc, "wqPrjCcTransaction"."transactionId" desc
            offset :offset rows fetch next :limit rows only
            """;

    private static final String FETCH = """
            select "wqPrjCcTransaction"."userId", "wqPrjCcTransaction"."transactionId", "wqPrjCcTransaction"."source", "wqPrjCcTransaction"."sourceId",
                   "wqPrjCcTransaction"."occurredAt", "wqPrjCcTransaction"."productId", "wqPrjCcTransaction"."batchId",
                   "wqPrjCcTransaction"."subBatchId", "wqPrjCcTransaction"."type", "wqPrjCcTransaction"."amount", "wqPrjCcTransaction"."currency",
                   "wqPrjCcTransaction"."balanceAfter", "wqPrjCcTransaction"."cancelled", "wqPrjCcTransaction"."cancelledBy",
                   "wqPrjCcTransaction"."cancels", "wqPrjCcTransaction"."detail", "wqPrjCcTransaction"."ingestedAt", "wqPrjCcTransaction"."productType"
            from "wqPrjCcTransaction"
            join unnest(cast(:ids as bigint[])) as "wanted" ("id") on "wqPrjCcTransaction"."transactionId" = "wanted"."id"
            where ("wqPrjCcTransaction"."ownerType" = :ownerType and "wqPrjCcTransaction"."userId" = :userId
                   and "wqPrjCcTransaction"."occurredAt" between cast(:min as timestamptz) and cast(:max as timestamptz))
            """;

    private final CcTransactionMapper mapper;

    public CcLevel0Query(CcTransactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }

    @Override
    public ReadPolicy policy() {
        return POLICY;
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
                .param("ownerType", POLICY.ownerType())
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
                ("wqPrjCcTransaction"."ownerType" = :ownerType and "wqPrjCcTransaction"."userId" = :userId \
                and "wqPrjCcTransaction"."occurredAt" >= cast(:lo as timestamptz) \
                and "wqPrjCcTransaction"."occurredAt" < cast(:hi as timestamptz)""");
        c.append(POLICY.zeroAmountFilter("\"wqPrjCcTransaction\""));
        if (f.excludeCancelled()) {
            c.append(" and not (\"wqPrjCcTransaction\".\"cancelled\")");
        }
        if (!f.productTypes().isEmpty()) {
            c.append(" and \"wqPrjCcTransaction\".\"productType\" in (:productTypes)");
        }
        if (!f.typeCodes().isEmpty()) {
            c.append(" and \"wqPrjCcTransaction\".\"type\" in (:typeCodes)");
        }
        return c.append(')').toString();
    }

    private static JdbcClient.StatementSpec range(JdbcClient.StatementSpec spec, Level0Filter f, Instant lo, Instant hi) {
        spec = spec.param("ownerType", POLICY.ownerType()).param("userId", f.userId())
                .param("lo", utc(lo)).param("hi", utc(hi));
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
