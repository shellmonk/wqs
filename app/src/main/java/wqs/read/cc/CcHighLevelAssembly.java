package wqs.read.cc;

import static wqs.db.generated.Tables.WQ_CC_ITEM;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import wqs.db.TxTypeRegistry;
import wqs.domain.ViewKind;
import wqs.read.HighLevelItemQuery;
import wqs.read.HighLevelKey;
import wqs.read.ItemEntry;
import wqs.read.TypeCodes;

/** Level-1/2 assembly reads of the operator (cc) view's items; touches only WQ_CC_ITEM. */
@Component
public class CcHighLevelAssembly implements HighLevelItemQuery {

    private final CcItemMapper mapper;
    private final TxTypeRegistry txTypes;

    public CcHighLevelAssembly(CcItemMapper mapper, TxTypeRegistry txTypes) {
        this.mapper = mapper;
        this.txTypes = txTypes;
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }

    @Override
    public List<ItemEntry> sessionItems(DSLContext tx, long userId, Set<String> batchIds, Instant lo, Instant hi,
                                        Set<Short> typeIds, boolean excludeCancelled) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        Table<?> wanted = unnest(batchIds);
        Condition c = WQ_CC_ITEM.userId.eq(userId).and(WQ_CC_ITEM.occurredAt.between(utc(lo), utc(hi)));
        if (excludeCancelled) {
            c = c.and(DSL.not(WQ_CC_ITEM.cancelled));
        }
        if (!typeIds.isEmpty()) {
            c = c.and(WQ_CC_ITEM.type.in(typeIds));
        }
        TypeCodes codes = id -> txTypes.codeOf(tx, id);
        return tx.select(WQ_CC_ITEM.fields())
                .from(WQ_CC_ITEM)
                .join(wanted).on(WQ_CC_ITEM.batchId.eq(wanted.field("id", String.class)))
                .where(c)
                .orderBy(WQ_CC_ITEM.occurredAt.desc(), WQ_CC_ITEM.itemId.desc())
                .fetchInto(WQ_CC_ITEM)
                .map(r -> mapper.toEntry(r, codes));
    }

    @Override
    public Map<HighLevelKey, ItemEntry> unsessioned(DSLContext tx, long userId, List<HighLevelKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Instant min = keys.stream().map(HighLevelKey::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant max = keys.stream().map(HighLevelKey::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        Set<HighLevelKey> want = Set.copyOf(keys);
        Table<?> wanted = unnest(keys.stream().map(HighLevelKey::refId).collect(LinkedHashSet::new, Set::add, Set::addAll));
        TypeCodes codes = id -> txTypes.codeOf(tx, id);
        Map<HighLevelKey, ItemEntry> out = new HashMap<>();
        tx.select(WQ_CC_ITEM.fields())
                .from(WQ_CC_ITEM)
                .join(wanted).on(WQ_CC_ITEM.sourceId.eq(wanted.field("id", String.class)))
                .where(WQ_CC_ITEM.userId.eq(userId))
                .and(WQ_CC_ITEM.occurredAt.between(utc(min), utc(max)))
                .and(WQ_CC_ITEM.batchId.isNull())
                .fetchInto(WQ_CC_ITEM)
                .forEach(r -> {
                    // sourceId is unique per source only: the full key picks the page's row.
                    HighLevelKey k = new HighLevelKey(r.getOccurredAt().toInstant(), r.getSource(), r.getSourceId());
                    if (want.contains(k)) {
                        out.put(k, mapper.toEntry(r, codes));
                    }
                });
        return out;
    }

    /** One array bind, joined through unnest(?) as {@link CcLevel0Query#fetch} does. */
    private static Table<?> unnest(Set<String> ids) {
        return DSL.unnest(DSL.val(ids.toArray(String[]::new), SQLDataType.CLOB.getArrayDataType())).as("wanted", "id");
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
