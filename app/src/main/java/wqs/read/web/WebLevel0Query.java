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
import org.jooq.Row2;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import wqs.domain.ViewKind;
import wqs.read.ItemEntry;
import wqs.read.ItemKey;
import wqs.read.Level0Filter;
import wqs.read.Level0Query;

/** Level-0 reads of the web view; touches only WQ_WEB_ITEM. */
@Component
public class WebLevel0Query implements Level0Query {

    private final WebItemMapper mapper;

    public WebLevel0Query(WebItemMapper mapper) {
        this.mapper = mapper;
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
        List<Row2<OffsetDateTime, Long>> rows = new ArrayList<>(keys.size());
        for (ItemKey k : keys) {
            rows.add(DSL.row(utc(k.occurredAt()), k.itemId()));
        }
        Map<ItemKey, ItemEntry> byKey = new HashMap<>();
        tx.selectFrom(WQ_WEB_ITEM)
                .where(WQ_WEB_ITEM.userId.eq(userId))
                .and(WQ_WEB_ITEM.occurredAt.between(utc(min), utc(max)))
                .and(DSL.row(WQ_WEB_ITEM.occurredAt, WQ_WEB_ITEM.itemId).in(rows))
                .fetch()
                .forEach(r -> byKey.put(
                        new ItemKey(r.getOccurredAt().toInstant(), r.getItemId()), mapper.toEntry(r)));
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
        if (f.productId() != null) {
            c = c.and(WQ_WEB_ITEM.productId.eq(f.productId()));
        }
        if (f.excludeCancelled()) {
            c = c.and(DSL.not(WQ_WEB_ITEM.cancelled));
        }
        return c;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
