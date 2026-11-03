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
import wqs.read.HighLevelItemQuery;
import wqs.read.HighLevelKey;
import wqs.read.ItemEntry;

/** Level-1/2 assembly reads of the operator (cc) view's items; of the item tables, touches only "wqCcItem". */
@Component
public class CcHighLevelAssembly implements HighLevelItemQuery {

    private static final String ITEM_COLUMNS = """
            "wqCcItem"."userId", "wqCcItem"."itemId", "wqCcItem"."source", "wqCcItem"."sourceId",
            "wqCcItem"."occurredAt", "wqCcItem"."productId", "wqCcItem"."batchId", "wqCcItem"."subBatchId",
            "wqCcItem"."type", "wqCcItem"."amount", "wqCcItem"."currency", "wqCcItem"."balanceAfter",
            "wqCcItem"."cancelled", "wqCcItem"."cancelledBy", "wqCcItem"."cancels", "wqCcItem"."detail",
            "wqCcItem"."ingestedAt", "wqCcItem"."productType", "wqTxType"."code"
            """;

    // Per session: ("userId", "batchId", "occurredAt") probes of "wqCcItem_batch", each from the
    // session's own start; the OR keeps its whole window (even past the range end, as its totals
    // count it) plus its late cancellations before the range end. %1$s: the excl predicate, %2$s:
    // the type-code predicate -- fixed fragments, never values.
    private static final String SESSION_ITEMS = "select " + ITEM_COLUMNS + """
            from unnest(cast(:ids as text[])) as "wanted" ("id")
            join "wqSession" on ("wqSession"."userId" = :userId and "wqSession"."batchId" = "wanted"."id"
                   and "wqSession"."startedAt" between cast(:startedFrom as timestamptz)
                                               and cast(:startedTo as timestamptz))
            join "wqCcItem" on ("wqCcItem"."userId" = :userId and "wqCcItem"."batchId" = "wqSession"."batchId"
                   and "wqCcItem"."occurredAt" >= "wqSession"."startedAt"
                   and ("wqCcItem"."occurredAt" <= "wqSession"."endedAt"
                        or ("wqCcItem"."cancels" is not null and "wqCcItem"."occurredAt" < cast(:to as timestamptz)))%1$s)
            join "wqTxType" on ("wqTxType"."typeId" = "wqCcItem"."type"%2$s)
            order by "wqCcItem"."occurredAt" desc, "wqCcItem"."itemId" desc
            """;
    private static final String NOT_CANCELLED = " and not (\"wqCcItem\".\"cancelled\")";
    private static final String TYPE_CODES = " and \"wqTxType\".\"code\" = any (cast(:typeCodes as text[]))";

    // Point lookups: the keys' exact "occurredAt" values probe the ("userId", "occurredAt")
    // indexes; sourceId and the full key in Java narrow to the page's rows (final review F1).
    private static final String UNSESSIONED = "select " + ITEM_COLUMNS + """
            from "wqCcItem"
            join "wqTxType" on "wqTxType"."typeId" = "wqCcItem"."type"
            where ("wqCcItem"."userId" = :userId
                   and "wqCcItem"."occurredAt" = any (cast(:ats as timestamp with time zone[]))
                   and "wqCcItem"."sourceId" = any (cast(:sourceIds as text[]))
                   and "wqCcItem"."batchId" is null)
            """;

    private final CcItemMapper mapper;

    public CcHighLevelAssembly(CcItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }

    @Override
    public List<ItemEntry> sessionItems(JdbcClient tx, long userId, Set<String> batchIds, Instant startedFrom,
                                        Instant startedTo, Instant to, Set<String> typeCodes,
                                        boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        JdbcClient.StatementSpec spec = tx.sql(SESSION_ITEMS.formatted(
                        excludeCancelled ? NOT_CANCELLED : "", typeCodes.isEmpty() ? "" : TYPE_CODES))
                .param("ids", array(batchIds))
                .param("userId", userId)
                .param("startedFrom", utc(startedFrom))
                .param("startedTo", utc(startedTo))
                .param("to", utc(to));
        if (!typeCodes.isEmpty()) {
            spec = spec.param("typeCodes", array(typeCodes));
        }
        return spec.query((rs, n) -> {
            String code = rs.getString("code");
            return mapper.toEntry(rs, id -> code);
        }).list();
    }

    @Override
    public Map<HighLevelKey, ItemEntry> unsessioned(JdbcClient tx, long userId, List<HighLevelKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Set<HighLevelKey> want = Set.copyOf(keys);
        // timestamptz text as jOOQ bound it; one array bind, whatever the page size.
        String[] ats = keys.stream().map(k -> utc(k.occurredAt())).distinct()
                .map(OffsetDateTime::toString).toArray(String[]::new);
        Set<String> sourceIds = new LinkedHashSet<>();
        keys.forEach(k -> sourceIds.add(k.refId()));
        Map<HighLevelKey, ItemEntry> out = new HashMap<>();
        tx.sql(UNSESSIONED)
                .param("userId", userId)
                .param("ats", ats)
                .param("sourceIds", array(sourceIds))
                .query(rs -> {
                    // sourceId is unique per source only: the full key picks the page's row.
                    HighLevelKey k = new HighLevelKey(rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
                            rs.getShort("source"), rs.getString("sourceId"));
                    if (want.contains(k)) {
                        String code = rs.getString("code");
                        out.put(k, mapper.toEntry(rs, id -> code));
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
