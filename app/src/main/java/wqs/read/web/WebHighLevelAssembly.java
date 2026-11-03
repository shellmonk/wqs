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
import wqs.read.HighLevelItemQuery;
import wqs.read.HighLevelKey;
import wqs.read.ItemEntry;

/** Level-1/2 assembly reads of the web view's items; of the item tables, touches only "wqWebItem". */
@Component
public class WebHighLevelAssembly implements HighLevelItemQuery {

    private static final String ITEM_COLUMNS = """
            "wqWebItem"."userId", "wqWebItem"."itemId", "wqWebItem"."source", "wqWebItem"."sourceId",
            "wqWebItem"."occurredAt", "wqWebItem"."productId", "wqWebItem"."batchId", "wqWebItem"."subBatchId",
            "wqWebItem"."type", "wqWebItem"."amount", "wqWebItem"."currency", "wqWebItem"."balanceAfter",
            "wqWebItem"."cancelled", "wqWebItem"."cancelledBy", "wqWebItem"."cancels", "wqWebItem"."detail",
            "wqWebItem"."ingestedAt", "wqWebItem"."productType", "wqTxType"."code"
            """;

    // Per session: ("userId", "batchId", "occurredAt") probes of "wqWebItem_batch", each from the
    // session's own start; the OR keeps its whole window (even past the range end, as its totals
    // count it) plus its late cancellations before the range end. %1$s: the excl predicate, %2$s:
    // the type-code predicate -- fixed fragments, never values.
    private static final String SESSION_ITEMS = "select " + ITEM_COLUMNS + """
            from unnest(cast(:ids as text[])) as "wanted" ("id")
            join "wqSession" on ("wqSession"."userId" = :userId and "wqSession"."batchId" = "wanted"."id"
                   and "wqSession"."startedAt" between cast(:startedFrom as timestamptz)
                                               and cast(:startedTo as timestamptz))
            join "wqWebItem" on ("wqWebItem"."userId" = :userId and "wqWebItem"."batchId" = "wqSession"."batchId"
                   and "wqWebItem"."occurredAt" >= "wqSession"."startedAt"
                   and ("wqWebItem"."occurredAt" <= "wqSession"."endedAt"
                        or ("wqWebItem"."cancels" is not null and "wqWebItem"."occurredAt" < cast(:to as timestamptz)))%1$s)
            join "wqTxType" on ("wqTxType"."typeId" = "wqWebItem"."type"%2$s)
            order by "wqWebItem"."occurredAt" desc, "wqWebItem"."itemId" desc
            """;
    private static final String NOT_CANCELLED = " and not (\"wqWebItem\".\"cancelled\")";
    private static final String TYPE_CODES = " and \"wqTxType\".\"code\" = any (cast(:typeCodes as text[]))";

    // Point lookups: the keys' exact "occurredAt" values probe the ("userId", "occurredAt")
    // indexes; sourceId and the full key in Java narrow to the page's rows (final review F1).
    private static final String UNSESSIONED = "select " + ITEM_COLUMNS + """
            from "wqWebItem"
            join "wqTxType" on "wqTxType"."typeId" = "wqWebItem"."type"
            where ("wqWebItem"."userId" = :userId
                   and "wqWebItem"."occurredAt" = any (cast(:ats as timestamp with time zone[]))
                   and "wqWebItem"."sourceId" = any (cast(:sourceIds as text[]))
                   and "wqWebItem"."batchId" is null)
            """;

    private final WebItemMapper mapper;

    public WebHighLevelAssembly(WebItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
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
