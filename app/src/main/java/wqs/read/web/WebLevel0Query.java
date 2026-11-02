package wqs.read.web;

import static wqs.db.generated.Tables.WQ_WEB_ITEM;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import wqs.db.TxTypeRegistry;
import wqs.domain.ViewKind;
import wqs.read.ItemEntry;
import wqs.read.ItemKey;
import wqs.read.Level0Filter;
import wqs.read.Level0Query;
import wqs.read.TypeCodes;

/** Level-0 reads of the web view; touches only WQ_WEB_ITEM. */
@Component
public class WebLevel0Query implements Level0Query {

    private final WebItemMapper mapper;
    private final TxTypeRegistry txTypes;

    public WebLevel0Query(WebItemMapper mapper, TxTypeRegistry txTypes) {
        this.mapper = mapper;
        this.txTypes = txTypes;
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }

    @Override
    public long count(DSLContext tx, Level0Filter f, Instant lo, Instant hi) {
        Long n = tx.select(DSL.count())
                .from(WQ_WEB_ITEM)
                .where(range(f, lo, hi))
                .fetchOne(0, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<ItemKey> keys(
            DSLContext tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
        return tx.select(WQ_WEB_ITEM.occurredAt, WQ_WEB_ITEM.itemId)
                .from(WQ_WEB_ITEM)
                .where(range(f, lo, hi))
                .orderBy(WQ_WEB_ITEM.occurredAt.desc(), WQ_WEB_ITEM.itemId.desc())
                .offset(offset)
                .limit(limit)
                .fetch(r -> new ItemKey(r.value1().toInstant(), r.value2()));
    }

    @Override
    public List<ItemEntry> fetch(DSLContext tx, long userId, List<ItemKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Instant min = keys.stream().map(ItemKey::occurredAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant max = keys.stream().map(ItemKey::occurredAt).max(Comparator.naturalOrder()).orElseThrow();
        // One array bind instead of 2 binds per key: page size is unbounded. Joined through
        // unnest(?), which Postgres evaluates once; "itemId" = any(?) re-evaluates the text-bound
        // array per row (array_in is not constant-folded), O(rows x keys). itemId comes from a
        // global sequence, so within the user and the occurredAt window it identifies one row.
        Long[] ids = keys.stream().map(ItemKey::itemId).toArray(Long[]::new);
        Table<?> wanted = DSL.unnest(DSL.val(ids, SQLDataType.BIGINT.getArrayDataType())).as("wanted", "id");
        // Codes resolve through the caller's transaction (M2), never the registry's writer DSL.
        TypeCodes codes = id -> txTypes.codeOf(tx, id);
        Map<ItemKey, ItemEntry> byKey = new HashMap<>();
        tx.select(WQ_WEB_ITEM.fields())
                .from(WQ_WEB_ITEM)
                .join(wanted).on(WQ_WEB_ITEM.itemId.eq(wanted.field("id", Long.class)))
                .where(WQ_WEB_ITEM.userId.eq(userId))
                .and(WQ_WEB_ITEM.occurredAt.between(utc(min), utc(max)))
                .fetchInto(WQ_WEB_ITEM)
                .forEach(r -> byKey.put(
                        new ItemKey(r.getOccurredAt().toInstant(), r.getItemId()), mapper.toEntry(r, codes)));
        List<ItemEntry> out = new ArrayList<>(keys.size());
        for (ItemKey k : keys) {
            ItemEntry e = byKey.get(k);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static Condition range(Level0Filter f, Instant lo, Instant hi) {
        Condition c = WQ_WEB_ITEM.userId.eq(f.userId())
                .and(WQ_WEB_ITEM.occurredAt.ge(utc(lo)))
                .and(WQ_WEB_ITEM.occurredAt.lt(utc(hi)));
        if (f.excludeCancelled()) {
            c = c.and(DSL.not(WQ_WEB_ITEM.cancelled));
        }
        if (!f.productTypes().isEmpty()) {
            c = c.and(WQ_WEB_ITEM.productType.in(f.productTypes()));
        }
        if (!f.typeIds().isEmpty()) {
            c = c.and(WQ_WEB_ITEM.type.in(f.typeIds()));
        }
        return c;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
