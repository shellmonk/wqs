package wqs.read.web;

import static wqs.db.generated.Tables.WQ_SESSION;
import static wqs.db.generated.Tables.WQ_TX_TYPE;
import static wqs.db.generated.Tables.WQ_WEB_ITEM;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import wqs.domain.ViewKind;
import wqs.read.HighLevelItemQuery;
import wqs.read.HighLevelKey;
import wqs.read.ItemEntry;

/** Level-1/2 assembly reads of the web view's items; of the item tables, touches only WQ_WEB_ITEM. */
@Component
public class WebHighLevelAssembly implements HighLevelItemQuery {

    private final WebItemMapper mapper;

    public WebHighLevelAssembly(WebItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }

    @Override
    public List<ItemEntry> sessionItems(DSLContext tx, long userId, Set<String> batchIds, Instant startedFrom,
                                        Instant startedTo, Instant to, Set<String> typeCodes,
                                        boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        Table<?> wanted = unnest(batchIds);
        // Per session: ("userId", "batchId", "occurredAt") probes of "wqWebItem_batch", each from
        // the session's own start; the OR keeps its whole window (even past the range end, as its
        // totals count it) plus its late cancellations before the range end.
        Condition member = WQ_WEB_ITEM.userId.eq(userId)
                .and(WQ_WEB_ITEM.batchId.eq(WQ_SESSION.batchId))
                .and(WQ_WEB_ITEM.occurredAt.ge(WQ_SESSION.startedAt))
                .and(WQ_WEB_ITEM.occurredAt.le(WQ_SESSION.endedAt)
                        .or(WQ_WEB_ITEM.cancels.isNotNull().and(WQ_WEB_ITEM.occurredAt.lt(utc(to)))));
        if (excludeCancelled) {
            member = member.and(DSL.not(WQ_WEB_ITEM.cancelled));
        }
        Condition type = WQ_TX_TYPE.typeId.eq(WQ_WEB_ITEM.type);
        if (!typeCodes.isEmpty()) {
            type = type.and(WQ_TX_TYPE.code.eq(DSL.any(array(typeCodes))));
        }
        return tx.select(WQ_WEB_ITEM.fields())
                .select(WQ_TX_TYPE.code)
                .from(wanted)
                .join(WQ_SESSION).on(WQ_SESSION.userId.eq(userId)
                        .and(WQ_SESSION.batchId.eq(wanted.field("id", String.class)))
                        .and(WQ_SESSION.startedAt.between(utc(startedFrom), utc(startedTo))))
                .join(WQ_WEB_ITEM).on(member)
                .join(WQ_TX_TYPE).on(type)
                .orderBy(WQ_WEB_ITEM.occurredAt.desc(), WQ_WEB_ITEM.itemId.desc())
                .fetch(r -> {
                    String code = r.get(WQ_TX_TYPE.code);
                    return mapper.toEntry(r.into(WQ_WEB_ITEM), id -> code);
                });
    }

    @Override
    public Map<HighLevelKey, ItemEntry> unsessioned(DSLContext tx, long userId, List<HighLevelKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Set<HighLevelKey> want = Set.copyOf(keys);
        // Point lookups: the keys' exact "occurredAt" values probe the ("userId", "occurredAt")
        // indexes; sourceId and the full key in Java narrow to the page's rows (final review F1).
        OffsetDateTime[] ats = keys.stream().map(k -> utc(k.occurredAt())).distinct().toArray(OffsetDateTime[]::new);
        Set<String> sourceIds = new java.util.LinkedHashSet<>();
        keys.forEach(k -> sourceIds.add(k.refId()));
        Map<HighLevelKey, ItemEntry> out = new HashMap<>();
        tx.select(WQ_WEB_ITEM.fields())
                .select(WQ_TX_TYPE.code)
                .from(WQ_WEB_ITEM)
                .join(WQ_TX_TYPE).on(WQ_TX_TYPE.typeId.eq(WQ_WEB_ITEM.type))
                .where(WQ_WEB_ITEM.userId.eq(userId))
                .and(WQ_WEB_ITEM.occurredAt.eq(DSL.any(DSL.val(ats, SQLDataType.TIMESTAMPWITHTIMEZONE.getArrayDataType()))))
                .and(WQ_WEB_ITEM.sourceId.eq(DSL.any(array(sourceIds))))
                .and(WQ_WEB_ITEM.batchId.isNull())
                .fetch()
                .forEach(r -> {
                    // sourceId is unique per source only: the full key picks the page's row.
                    var item = r.into(WQ_WEB_ITEM);
                    HighLevelKey k = new HighLevelKey(item.getOccurredAt().toInstant(), item.getSource(), item.getSourceId());
                    if (want.contains(k)) {
                        String code = r.get(WQ_TX_TYPE.code);
                        out.put(k, mapper.toEntry(item, id -> code));
                    }
                });
        return out;
    }

    /** One array bind, joined through unnest(?) as {@link WebLevel0Query#fetch} does. */
    private static Table<?> unnest(Set<String> ids) {
        return DSL.unnest(array(ids)).as("wanted", "id");
    }

    private static org.jooq.Field<String[]> array(Set<String> values) {
        return DSL.val(values.toArray(String[]::new), SQLDataType.CLOB.getArrayDataType());
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
