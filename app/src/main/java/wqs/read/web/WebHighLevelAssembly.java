package wqs.read.web;

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

/** Level-1/2 assembly reads of the web view's transactions; of the transaction relations, reads only the "wqPrjWebTransaction" view. */
@Component
public class WebHighLevelAssembly implements HighLevelTransactionQuery {

    private static final String TRANSACTION_COLUMNS = """
            "wqPrjWebTransaction"."userId", "wqPrjWebTransaction"."transactionId", "wqPrjWebTransaction"."source", "wqPrjWebTransaction"."sourceId",
            "wqPrjWebTransaction"."occurredAt", "wqPrjWebTransaction"."productId", "wqPrjWebTransaction"."batchId", "wqPrjWebTransaction"."subBatchId",
            "wqPrjWebTransaction"."type", "wqPrjWebTransaction"."amount", "wqPrjWebTransaction"."currency", "wqPrjWebTransaction"."balanceAfter",
            "wqPrjWebTransaction"."cancelled", "wqPrjWebTransaction"."cancelledBy", "wqPrjWebTransaction"."cancels", "wqPrjWebTransaction"."detail",
            "wqPrjWebTransaction"."ingestedAt", "wqPrjWebTransaction"."productType"
            """;

    // Per session: ("userId", "batchId", "occurredAt") probes of "wqPrjTransaction_batch" (through the view), each from the
    // session's own start; the OR keeps its whole window (even past the range end, as its totals
    // count it) plus its late cancellations before the range end. %1$s: the excl predicate, %2$s:
    // the type-code predicate -- fixed fragments, never values.
    private static final String SESSION_TRANSACTIONS = "select " + TRANSACTION_COLUMNS + """
            from unnest(cast(:ids as text[])) as "wanted" ("id")
            join "wqPrjSession" on ("wqPrjSession"."userId" = :userId and "wqPrjSession"."batchId" = "wanted"."id"
                   and "wqPrjSession"."startedAt" between cast(:startedFrom as timestamptz)
                                               and cast(:startedTo as timestamptz))
            join "wqPrjWebTransaction" on ("wqPrjWebTransaction"."userId" = :userId and "wqPrjWebTransaction"."batchId" = "wqPrjSession"."batchId"
                   and "wqPrjWebTransaction"."occurredAt" >= "wqPrjSession"."startedAt"
                   and ("wqPrjWebTransaction"."occurredAt" <= "wqPrjSession"."endedAt"
                        or ("wqPrjWebTransaction"."cancels" is not null and "wqPrjWebTransaction"."occurredAt" < cast(:to as timestamptz)))%1$s%2$s)
            order by "wqPrjWebTransaction"."occurredAt" desc, "wqPrjWebTransaction"."transactionId" desc
            """;
    private static final String NOT_CANCELLED = " and not (\"wqPrjWebTransaction\".\"cancelled\")";
    private static final String TYPE_CODES = " and \"wqPrjWebTransaction\".\"type\" = any (cast(:typeCodes as text[]))";

    // Point lookups: the keys' exact "occurredAt" values probe the ("userId", "occurredAt")
    // indexes; sourceId and the full key in Java narrow to the page's rows (final review F1).
    private static final String UNSESSIONED = "select " + TRANSACTION_COLUMNS + """
            from "wqPrjWebTransaction"
            where ("wqPrjWebTransaction"."userId" = :userId
                   and "wqPrjWebTransaction"."occurredAt" = any (cast(:ats as timestamp with time zone[]))
                   and "wqPrjWebTransaction"."sourceId" = any (cast(:sourceIds as text[]))
                   and "wqPrjWebTransaction"."batchId" is null)
            """;

    private final WebTransactionMapper mapper;

    public WebHighLevelAssembly(WebTransactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }

    @Override
    public List<TransactionEntry> sessionTransactions(JdbcClient tx, long userId, Set<String> batchIds, Instant startedFrom,
                                        Instant startedTo, Instant to, Set<String> typeCodes,
                                        boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        JdbcClient.StatementSpec spec = tx.sql(SESSION_TRANSACTIONS.formatted(
                        excludeCancelled ? NOT_CANCELLED : "", typeCodes.isEmpty() ? "" : TYPE_CODES))
                .param("ids", array(batchIds))
                .param("userId", userId)
                .param("startedFrom", utc(startedFrom))
                .param("startedTo", utc(startedTo))
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
