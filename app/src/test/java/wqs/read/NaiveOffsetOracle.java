package wqs.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

/**
 * The permanent naive-OFFSET oracle (spec 8.1): straight SQL against the view's item table,
 * no counters, no decomposition. Deliberately obvious and never optimised -- it is what the
 * production reader is compared to.
 */
public final class NaiveOffsetOracle {

    private final DSLContext dsl;

    public NaiveOffsetOracle(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long count(ViewKind v, Level0Filter f, Instant from, Instant to) {
        List<Object> binds = new ArrayList<>();
        String sql = "select count(*) from \"" + v.itemTable() + "\" where " + where(f, from, to, binds);
        // A non-numeric or null result is a broken oracle, not zero: fail loudly.
        return ((Number) dsl.fetchValue(sql, binds.toArray())).longValue();
    }

    public List<ItemEntry> page(ViewKind v, Level0Filter f, Instant from, Instant to, long offset, int limit) {
        List<Object> binds = new ArrayList<>();
        String sql = "select *, \"detail\"::text as \"detailText\" from \"" + v.itemTable() + "\" where " + where(f, from, to, binds)
                + " order by \"occurredAt\" desc, \"itemId\" desc offset ? limit ?";
        binds.add(offset);
        binds.add(limit);
        return dsl.fetch(sql, binds.toArray()).map(NaiveOffsetOracle::entry);
    }

    private static String where(Level0Filter f, Instant from, Instant to, List<Object> binds) {
        StringBuilder w = new StringBuilder("\"userId\" = ? and \"occurredAt\" >= ?::timestamptz and \"occurredAt\" < ?::timestamptz");
        binds.add(f.userId());
        // ISO-8601 with Z: an explicit UTC instant, independent of the session TimeZone.
        binds.add(from.toString());
        binds.add(to.toString());
        if (f.excludeCancelled()) {
            w.append(" and not \"cancelled\"");
        }
        return w.toString();
    }

    /** Same field rules as the view mappers: kind is the source's name, detail the raw JSON. */
    private static ItemEntry entry(Record r) {
        return new ItemEntry(
                SourceKind.ofCode(r.get("source", Integer.class)).name(),
                r.get("sourceId", String.class),
                r.get("occurredAt", OffsetDateTime.class).toInstant(),
                r.get("productId", String.class),
                r.get("batchId", String.class),
                r.get("subBatchId", String.class),
                r.get("type", Short.class),
                r.get("amount", BigDecimal.class),
                r.get("currency", String.class),
                r.get("balanceAfter", BigDecimal.class),
                r.get("cancelled", Boolean.class),
                r.get("cancelledBy", String.class),
                r.get("cancels", String.class),
                r.get("detailText", String.class));
    }
}
