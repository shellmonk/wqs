package wqs.read;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Level-0 reads of "wqPrjTransaction" through one endpoint's {@link ReadPolicy} (plan D1): every
 * statement binds the policy's sources, owner type and zero-amount rule. Every predicate and sort
 * column of the count and key queries is in "wqPrjTransaction_cover" -- key ("ownerType", "userId",
 * "occurredAt" desc, "transactionId" desc), INCLUDE ("source", "productType", "type", "cancelled",
 * "zeroAmount") -- so both are index-only scans. One subclass per endpoint (spec 2.2 rule 4).
 */
public abstract class PolicyLevel0Query implements Level0Query {

    // %s: the filter's fixed fragments, never values.
    private static final String COUNT = """
            select count(*) from "wqPrjTransaction"
            where ("wqPrjTransaction"."ownerType" = :ownerType and "wqPrjTransaction"."userId" = :userId
                   and "wqPrjTransaction"."source" = any (cast(:sources as smallint[]))
                   and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                   and "wqPrjTransaction"."occurredAt" >= cast(:lo as timestamptz)
                   and "wqPrjTransaction"."occurredAt" < cast(:hi as timestamptz)%s)
            """;

    private static final String KEYS = """
            select "wqPrjTransaction"."occurredAt", "wqPrjTransaction"."transactionId" from "wqPrjTransaction"
            where ("wqPrjTransaction"."ownerType" = :ownerType and "wqPrjTransaction"."userId" = :userId
                   and "wqPrjTransaction"."source" = any (cast(:sources as smallint[]))
                   and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                   and "wqPrjTransaction"."occurredAt" >= cast(:lo as timestamptz)
                   and "wqPrjTransaction"."occurredAt" < cast(:hi as timestamptz)%s)
            order by "wqPrjTransaction"."occurredAt" desc, "wqPrjTransaction"."transactionId" desc
            offset :offset rows fetch next :limit rows only
            """;

    private static final String FETCH = """
            select "wqPrjTransaction"."userId", "wqPrjTransaction"."transactionId", "wqPrjTransaction"."source",
                   "wqPrjTransaction"."sourceId", "wqPrjTransaction"."occurredAt", "wqPrjTransaction"."productId",
                   "wqPrjTransaction"."batchId", "wqPrjTransaction"."subBatchId", "wqPrjTransaction"."type",
                   "wqPrjTransaction"."amount", "wqPrjTransaction"."currency", "wqPrjTransaction"."balanceAfter",
                   "wqPrjTransaction"."cancelled", "wqPrjTransaction"."cancelledBy", "wqPrjTransaction"."cancels",
                   "wqPrjTransaction"."detail", "wqPrjTransaction"."ingestedAt", "wqPrjTransaction"."productType"
            from "wqPrjTransaction"
            join unnest(cast(:ids as bigint[])) as "wanted" ("id") on "wqPrjTransaction"."transactionId" = "wanted"."id"
            join unnest(cast(:sources as smallint[])) as "shown" ("source") on "wqPrjTransaction"."source" = "shown"."source"
            where ("wqPrjTransaction"."ownerType" = :ownerType and "wqPrjTransaction"."userId" = :userId
                   and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                   and "wqPrjTransaction"."occurredAt" between cast(:min as timestamptz) and cast(:max as timestamptz))
            """;

    private static final String NOT_CANCELLED = " and not (\"wqPrjTransaction\".\"cancelled\")";
    private static final String PRODUCT_TYPES = " and \"wqPrjTransaction\".\"productType\" in (:productTypes)";
    private static final String TYPE_CODES = " and \"wqPrjTransaction\".\"type\" in (:typeCodes)";

    private final ReadPolicy policy;
    private final TransactionRowMapper mapper;

    protected PolicyLevel0Query(ReadPolicy policy, TransactionRowMapper mapper) {
        this.policy = policy;
        this.mapper = mapper;
    }

    @Override
    public final ReadPolicy policy() {
        return policy;
    }

    @Override
    public long count(JdbcClient tx, Level0Filter f, Instant lo, Instant hi) {
        return range(tx.sql(COUNT.formatted(filters(f))), f, lo, hi).query(Long.class).single();
    }

    @Override
    public List<TransactionKey> keys(
            JdbcClient tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
        return range(tx.sql(KEYS.formatted(filters(f))), f, lo, hi)
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
        // global sequence, so within the owner and the occurredAt window it identifies one row. The
        // policy's sources are joined the same way, for the same reason.
        long[] ids = keys.stream().mapToLong(TransactionKey::transactionId).toArray();
        Map<TransactionKey, TransactionEntry> byKey = new HashMap<>();
        policy.bind(tx.sql(FETCH))
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

    /** The filter's optional predicates, value-free: the filter only decides which fixed fragments appear. */
    private static String filters(Level0Filter f) {
        StringBuilder c = new StringBuilder();
        if (f.excludeCancelled()) {
            c.append(NOT_CANCELLED);
        }
        if (!f.productTypes().isEmpty()) {
            c.append(PRODUCT_TYPES);
        }
        if (!f.typeCodes().isEmpty()) {
            c.append(TYPE_CODES);
        }
        return c.toString();
    }

    private JdbcClient.StatementSpec range(JdbcClient.StatementSpec spec, Level0Filter f, Instant lo, Instant hi) {
        spec = policy.bind(spec).param("userId", f.userId()).param("lo", utc(lo)).param("hi", utc(hi));
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
