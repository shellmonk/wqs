package wqs.read.cc;

import static wqs.db.generated.Tables.WQ_CC_ITEM;
import static wqs.db.generated.Tables.WQ_SESSION;
import static wqs.db.generated.Tables.WQ_TX_TYPE;

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

/** Level-1/2 assembly reads of the operator (cc) view's items; of the item tables, touches only WQ_CC_ITEM. */
@Component
public class CcHighLevelAssembly implements HighLevelItemQuery {

    private final CcItemMapper mapper;

    public CcHighLevelAssembly(CcItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }

    @Override
    public List<ItemEntry> sessionItems(DSLContext tx, long userId, Set<String> batchIds, Instant startedFrom,
                                        Instant startedTo, Instant to, Set<String> typeCodes,
                                        boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        Table<?> wanted = unnest(batchIds);
        // Per session: ("userId", "batchId", "occurredAt") probes of "wqCcItem_batch", each from
        // the session's own start; the OR keeps only its window plus its late cancellations.
        Condition member = WQ_CC_ITEM.userId.eq(userId)
                .and(WQ_CC_ITEM.batchId.eq(WQ_SESSION.batchId))
                .and(WQ_CC_ITEM.occurredAt.ge(WQ_SESSION.startedAt))
                .and(WQ_CC_ITEM.occurredAt.lt(utc(to)))
                .and(WQ_CC_ITEM.occurredAt.le(WQ_SESSION.endedAt).or(WQ_CC_ITEM.cancels.isNotNull()));
        if (excludeCancelled) {
            member = member.and(DSL.not(WQ_CC_ITEM.cancelled));
        }
        Condition type = WQ_TX_TYPE.typeId.eq(WQ_CC_ITEM.type);
        if (!typeCodes.isEmpty()) {
            type = type.and(WQ_TX_TYPE.code.eq(DSL.any(array(typeCodes))));
        }
        return tx.select(WQ_CC_ITEM.fields())
                .select(WQ_TX_TYPE.code)
                .from(wanted)
                .join(WQ_SESSION).on(WQ_SESSION.userId.eq(userId)
                        .and(WQ_SESSION.batchId.eq(wanted.field("id", String.class)))
                        .and(WQ_SESSION.startedAt.between(utc(startedFrom), utc(startedTo))))
                .join(WQ_CC_ITEM).on(member)
                .join(WQ_TX_TYPE).on(type)
                .orderBy(WQ_CC_ITEM.occurredAt.desc(), WQ_CC_ITEM.itemId.desc())
                .fetch(r -> {
                    String code = r.get(WQ_TX_TYPE.code);
                    return mapper.toEntry(r.into(WQ_CC_ITEM), id -> code);
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
        tx.select(WQ_CC_ITEM.fields())
                .select(WQ_TX_TYPE.code)
                .from(WQ_CC_ITEM)
                .join(WQ_TX_TYPE).on(WQ_TX_TYPE.typeId.eq(WQ_CC_ITEM.type))
                .where(WQ_CC_ITEM.userId.eq(userId))
                .and(WQ_CC_ITEM.occurredAt.eq(DSL.any(DSL.val(ats, SQLDataType.TIMESTAMPWITHTIMEZONE.getArrayDataType()))))
                .and(WQ_CC_ITEM.sourceId.eq(DSL.any(array(sourceIds))))
                .and(WQ_CC_ITEM.batchId.isNull())
                .fetch()
                .forEach(r -> {
                    // sourceId is unique per source only: the full key picks the page's row.
                    var item = r.into(WQ_CC_ITEM);
                    HighLevelKey k = new HighLevelKey(item.getOccurredAt().toInstant(), item.getSource(), item.getSourceId());
                    if (want.contains(k)) {
                        String code = r.get(WQ_TX_TYPE.code);
                        out.put(k, mapper.toEntry(item, id -> code));
                    }
                });
        return out;
    }

    /** One array bind, joined through unnest(?) as {@link CcLevel0Query#fetch} does. */
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
