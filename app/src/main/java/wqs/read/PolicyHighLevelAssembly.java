package wqs.read;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Level-1/2 assembly reads of "wqPrjTransaction" through one endpoint's {@link ReadPolicy} (plan D1):
 * a page's session members and its standalone transactions, each in ONE statement bounded by the page.
 * One subclass per endpoint.
 */
public abstract class PolicyHighLevelAssembly implements HighLevelTransactionQuery {

    private static final String TRANSACTION_COLUMNS = """
            "wqPrjTransaction"."userId", "wqPrjTransaction"."transactionId", "wqPrjTransaction"."source",
            "wqPrjTransaction"."sourceId", "wqPrjTransaction"."occurredAt", "wqPrjTransaction"."productId",
            "wqPrjTransaction"."batchId", "wqPrjTransaction"."subBatchId", "wqPrjTransaction"."type",
            "wqPrjTransaction"."amount", "wqPrjTransaction"."currency", "wqPrjTransaction"."balanceAfter",
            "wqPrjTransaction"."cancelled", "wqPrjTransaction"."cancelledBy", "wqPrjTransaction"."cancels",
            "wqPrjTransaction"."detail", "wqPrjTransaction"."ingestedAt", "wqPrjTransaction"."productType"
            """;

    // Per session (its row found by primary key ("ownerType", "userId", "batchId")): probes of
    // "wqPrjTransaction_batch", each from the session's own start; the OR keeps its whole window (even past
    // the range end, as its totals count it) plus its late cancellations before the range end.
    // %1$s: the excl predicate, %2$s: the type-code predicate -- fixed fragments, never values.
    private static final String SESSION_TRANSACTIONS = "select " + TRANSACTION_COLUMNS + """
            from unnest(cast(:ids as text[])) as "wanted" ("id")
            join "wqPrjSession" on ("wqPrjSession"."ownerType" = :ownerType and "wqPrjSession"."userId" = :userId
                   and "wqPrjSession"."batchId" = "wanted"."id")
            join "wqPrjTransaction" on ("wqPrjTransaction"."ownerType" = :ownerType
                   and "wqPrjTransaction"."userId" = :userId
                   and "wqPrjTransaction"."batchId" = "wqPrjSession"."batchId"
                   and "wqPrjTransaction"."source" = any (cast(:sources as smallint[]))
                   and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                   and "wqPrjTransaction"."occurredAt" >= "wqPrjSession"."startedAt"
                   and ("wqPrjTransaction"."occurredAt" <= "wqPrjSession"."endedAt"
                        or ("wqPrjTransaction"."cancels" is not null
                            and "wqPrjTransaction"."occurredAt" < cast(:to as timestamptz)))%1$s%2$s)
            order by "wqPrjTransaction"."occurredAt" desc, "wqPrjTransaction"."transactionId" desc
            """;
    private static final String NOT_CANCELLED = " and not (\"wqPrjTransaction\".\"cancelled\")";
    private static final String TYPE_CODES = " and \"wqPrjTransaction\".\"type\" = any (cast(:typeCodes as text[]))";

    // Point lookups: the keys' exact "occurredAt" values probe the standalone index; the source and
    // sourceId, and the full key in Java, narrow to the page's rows (final review F1).
    private static final String STANDALONE = "select " + TRANSACTION_COLUMNS + """
            from "wqPrjTransaction"
            where ("wqPrjTransaction"."ownerType" = :ownerType and "wqPrjTransaction"."userId" = :userId
                   and "wqPrjTransaction"."source" = any (cast(:sources as smallint[]))
                   and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                   and "wqPrjTransaction"."occurredAt" = any (cast(:ats as timestamp with time zone[]))
                   and "wqPrjTransaction"."sourceId" = any (cast(:sourceIds as text[]))
                   and "wqPrjTransaction"."batchId" is null and "wqPrjTransaction"."cancels" is null)
            """;

    private final ReadPolicy policy;
    private final TransactionRowMapper mapper;

    protected PolicyHighLevelAssembly(ReadPolicy policy, TransactionRowMapper mapper) {
        this.policy = policy;
        this.mapper = mapper;
    }

    @Override
    public final ReadPolicy policy() {
        return policy;
    }

    @Override
    public List<TransactionEntry> sessionTransactions(JdbcClient tx, long userId, Set<String> batchIds,
                                                      Instant to, Set<String> typeCodes, boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        JdbcClient.StatementSpec spec = policy.bind(tx.sql(SESSION_TRANSACTIONS.formatted(
                        excludeCancelled ? NOT_CANCELLED : "", typeCodes.isEmpty() ? "" : TYPE_CODES)))
                .param("ids", array(batchIds))
                .param("userId", userId)
                .param("to", utc(to));
        if (!typeCodes.isEmpty()) {
            spec = spec.param("typeCodes", array(typeCodes));
        }
        return spec.query((rs, n) -> mapper.toEntry(rs)).list();
    }

    @Override
    public Map<HighLevelKey, TransactionEntry> unsessioned(JdbcClient tx, long userId, List<HighLevelKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Set<HighLevelKey> want = Set.copyOf(keys);
        // timestamptz as ISO-8601 text (UTC); one array bind, whatever the page size.
        String[] ats = keys.stream().map(k -> utc(k.occurredAt())).distinct()
                .map(OffsetDateTime::toString).toArray(String[]::new);
        Set<String> sourceIds = new LinkedHashSet<>();
        keys.forEach(k -> sourceIds.add(k.refId()));
        Map<HighLevelKey, TransactionEntry> out = new HashMap<>();
        policy.bind(tx.sql(STANDALONE))
                .param("userId", userId)
                .param("ats", ats)
                .param("sourceIds", array(sourceIds))
                .query(rs -> {
                    // sourceId is unique per source only: the full key picks the page's row.
                    HighLevelKey k = new HighLevelKey(rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
                            rs.getShort("source"), rs.getString("sourceId"));
                    if (want.contains(k)) {
                        out.put(k, mapper.toEntry(rs));
                    }
                });
        return out;
    }

    private static String[] array(Set<String> values) {
        return values.toArray(String[]::new);
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
