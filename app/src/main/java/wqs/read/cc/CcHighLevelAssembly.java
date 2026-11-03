package wqs.read.cc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.domain.ViewKind;
import wqs.read.HighLevelTransactionQuery;
import wqs.read.HighLevelKey;
import wqs.read.TransactionEntry;

/** Level-1/2 assembly reads of the operator (cc) view's transactions; of the transaction relations, reads only the "wqPrjCcTransaction" view. */
@Component
public class CcHighLevelAssembly implements HighLevelTransactionQuery {

    private static final String TRANSACTION_COLUMNS = """
            "wqPrjCcTransaction"."userId", "wqPrjCcTransaction"."transactionId", "wqPrjCcTransaction"."source", "wqPrjCcTransaction"."sourceId",
            "wqPrjCcTransaction"."occurredAt", "wqPrjCcTransaction"."productId", "wqPrjCcTransaction"."batchId", "wqPrjCcTransaction"."subBatchId",
            "wqPrjCcTransaction"."type", "wqPrjCcTransaction"."amount", "wqPrjCcTransaction"."currency", "wqPrjCcTransaction"."balanceAfter",
            "wqPrjCcTransaction"."cancelled", "wqPrjCcTransaction"."cancelledBy", "wqPrjCcTransaction"."cancels", "wqPrjCcTransaction"."detail",
            "wqPrjCcTransaction"."ingestedAt", "wqPrjCcTransaction"."productType"
            """;

    // Per session (its row found by primary key ("userId", "batchId")): ("userId", "batchId", "occurredAt") probes of "wqPrjTransaction_batch", each from the
    // session's own start; the OR keeps its whole window (even past the range end, as its totals
    // count it) plus its late cancellations before the range end. %1$s: the excl predicate, %2$s:
    // the type-code predicate -- fixed fragments, never values.
    private static final String SESSION_TRANSACTIONS = "select " + TRANSACTION_COLUMNS + """
            from unnest(cast(:ids as text[])) as "wanted" ("id")
            join "wqPrjSession" on ("wqPrjSession"."userId" = :userId and "wqPrjSession"."batchId" = "wanted"."id")
            join "wqPrjCcTransaction" on ("wqPrjCcTransaction"."userId" = :userId and "wqPrjCcTransaction"."batchId" = "wqPrjSession"."batchId"
                   and "wqPrjCcTransaction"."occurredAt" >= "wqPrjSession"."startedAt"
                   and ("wqPrjCcTransaction"."occurredAt" <= "wqPrjSession"."endedAt"
                        or ("wqPrjCcTransaction"."cancels" is not null and "wqPrjCcTransaction"."occurredAt" < cast(:to as timestamptz)))%1$s%2$s)
            order by "wqPrjCcTransaction"."occurredAt" desc, "wqPrjCcTransaction"."transactionId" desc
            """;
    private static final String NOT_CANCELLED = " and not (\"wqPrjCcTransaction\".\"cancelled\")";
    private static final String TYPE_CODES = " and \"wqPrjCcTransaction\".\"type\" = any (cast(:typeCodes as text[]))";

    // Point lookups: the keys' exact "occurredAt" values probe the ("userId", "occurredAt")
    // indexes; sourceId and the full key in Java narrow to the page's rows (final review F1).
    private static final String UNSESSIONED = "select " + TRANSACTION_COLUMNS + """
            from "wqPrjCcTransaction"
            where ("wqPrjCcTransaction"."userId" = :userId
                   and "wqPrjCcTransaction"."occurredAt" = any (cast(:ats as timestamp with time zone[]))
                   and "wqPrjCcTransaction"."sourceId" = any (cast(:sourceIds as text[]))
                   and "wqPrjCcTransaction"."batchId" is null)
            """;

    private final CcTransactionMapper mapper;

    public CcHighLevelAssembly(CcTransactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }

    @Override
    public List<TransactionEntry> sessionTransactions(JdbcClient tx, long userId, Set<String> batchIds,
                                        Instant to, Set<String> typeCodes, boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        JdbcClient.StatementSpec spec = tx.sql(SESSION_TRANSACTIONS.formatted(
                        excludeCancelled ? NOT_CANCELLED : "", typeCodes.isEmpty() ? "" : TYPE_CODES))
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
        tx.sql(UNSESSIONED)
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
