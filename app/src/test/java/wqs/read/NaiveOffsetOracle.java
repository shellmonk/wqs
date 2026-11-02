package wqs.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        return count(v, f, Set.of(), from, to);
    }

    public long count(ViewKind v, Level0Filter f, Set<String> typeCodes, Instant from, Instant to) {
        List<Object> binds = new ArrayList<>();
        String sql = "select count(*) from \"" + v.itemTable() + "\" where " + where(f, typeCodes, from, to, binds);
        // A non-numeric or null result is a broken oracle, not zero: fail loudly.
        return ((Number) dsl.fetchValue(sql, binds.toArray())).longValue();
    }

    public List<ItemEntry> page(ViewKind v, Level0Filter f, Instant from, Instant to, long offset, int limit) {
        return page(v, f, Set.of(), from, to, offset, limit);
    }

    /** {@code typeCodes}: the transaction-type filter as codes, matched through "wqTxType" itself. */
    public List<ItemEntry> page(
            ViewKind v, Level0Filter f, Set<String> typeCodes, Instant from, Instant to, long offset, int limit) {
        List<Object> binds = new ArrayList<>();
        String sql = "select i.*, i.\"detail\"::text as \"detailText\", t.\"code\" as \"typeCode\" from \"" + v.itemTable()
                + "\" i join \"wqTxType\" t on t.\"typeId\" = i.\"type\" where "
                + where(f, typeCodes, from, to, binds)
                + " order by i.\"occurredAt\" desc, i.\"itemId\" desc offset ? limit ?";
        binds.add(offset);
        binds.add(limit);
        return dsl.fetch(sql, binds.toArray()).map(NaiveOffsetOracle::entry);
    }

    private static String where(
            Level0Filter f, Set<String> typeCodes, Instant from, Instant to, List<Object> binds) {
        StringBuilder w = new StringBuilder("\"userId\" = ? and \"occurredAt\" >= ?::timestamptz and \"occurredAt\" < ?::timestamptz");
        binds.add(f.userId());
        // ISO-8601 with Z: an explicit UTC instant, independent of the session TimeZone.
        binds.add(from.toString());
        binds.add(to.toString());
        if (f.excludeCancelled()) {
            w.append(" and not \"cancelled\"");
        }
        if (!f.productTypes().isEmpty()) {
            w.append(" and \"productType\" = any(?)");
            binds.add(f.productTypes().toArray(String[]::new));
        }
        if (!f.typeIds().isEmpty()) {
            w.append(" and \"type\" = any(?)");
            binds.add(f.typeIds().toArray(Short[]::new));
        }
        if (!typeCodes.isEmpty()) {
            w.append(" and \"type\" in (select \"typeId\" from \"wqTxType\" where \"code\" = any(?))");
            binds.add(typeCodes.toArray(String[]::new));
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
                r.get("productType", String.class),
                r.get("batchId", String.class),
                r.get("subBatchId", String.class),
                r.get("typeCode", String.class),
                r.get("amount", BigDecimal.class),
                r.get("currency", String.class),
                r.get("balanceAfter", BigDecimal.class),
                r.get("cancelled", Boolean.class),
                r.get("cancelledBy", String.class),
                r.get("cancels", String.class),
                r.get("detailText", String.class));
    }
}
