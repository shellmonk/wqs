package wqs.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.db.TestSql;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

/**
 * The permanent naive-OFFSET oracle (spec 8.1): straight SQL against the view's transaction table (level
 * 0) or high-level page index (levels 1-2), no counters, no decomposition. Deliberately obvious and never optimised -- it is what the
 * production reader is compared to.
 */
public final class NaiveOffsetOracle {

    private final TestSql sql;

    public NaiveOffsetOracle(JdbcClient jdbc) {
        this.sql = new TestSql(jdbc);
    }

    public long count(ViewKind v, Level0Filter f, Instant from, Instant to) {
        return count(v, f, Set.of(), from, to);
    }

    public long count(ViewKind v, Level0Filter f, Set<String> typeCodes, Instant from, Instant to) {
        List<Object> binds = new ArrayList<>();
        String sql = "select count(*) from \"" + v.transactionTable() + "\" where " + where(f, typeCodes, from, to, binds);
        // A non-numeric or null result is a broken oracle, not zero: fail loudly.
        return ((Number) this.sql.value(sql, Object.class, binds.toArray())).longValue();
    }

    public List<TransactionEntry> page(ViewKind v, Level0Filter f, Instant from, Instant to, long offset, int limit) {
        return page(v, f, Set.of(), from, to, offset, limit);
    }

    /** {@code typeCodes}: the transaction-type filter as codes, matched as the stored text. */
    public List<TransactionEntry> page(
            ViewKind v, Level0Filter f, Set<String> typeCodes, Instant from, Instant to, long offset, int limit) {
        List<Object> binds = new ArrayList<>();
        String sql = "select i.*, i.\"detail\"::text as \"detailText\", i.\"type\" as \"typeCode\" from \"" + v.transactionTable()
                + "\" i where "
                + where(f, typeCodes, from, to, binds)
                + " order by i.\"occurredAt\" desc, i.\"transactionId\" desc offset ? limit ?";
        binds.add(offset);
        binds.add(limit);
        return this.sql.rows(sql, binds.toArray()).stream().map(NaiveOffsetOracle::entry).toList();
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
        if (!f.typeCodes().isEmpty()) {
            w.append(" and \"type\" = any(?)");
            binds.add(f.typeCodes().toArray(String[]::new));
        }
        if (!typeCodes.isEmpty()) {
            w.append(" and \"type\" = any(?)");
            binds.add(typeCodes.toArray(String[]::new));
        }
        return w.toString();
    }

    // ------------------------------------------------------------------ high-level (levels 1-2)

    /** Live count of the view's page-index rows; no counters, no decomposition. */
    public long highLevelCount(ViewKind v, HighLevelFilter f, Instant from, Instant to) {
        List<Object> binds = new ArrayList<>();
        String sql = "select count(*) from \"" + highLevelTable(v) + "\" where " + highLevelWhere(f, from, to, binds);
        return ((Number) this.sql.value(sql, Object.class, binds.toArray())).longValue();
    }

    /** Plain {@code order by ... offset ... limit} over the page index, in the level-1/2 total order. */
    public List<HighLevelKey> highLevelPage(
            ViewKind v, HighLevelFilter f, Instant from, Instant to, long offset, int limit) {
        List<Object> binds = new ArrayList<>();
        String sql = "select \"occurredAt\", \"kind\", \"refId\" from \"" + highLevelTable(v) + "\" where "
                + highLevelWhere(f, from, to, binds)
                + " order by \"occurredAt\" desc, \"kind\" desc, \"refId\" desc offset ? limit ?";
        binds.add(offset);
        binds.add(limit);
        return this.sql.rows(sql, binds.toArray()).stream().map(r -> new HighLevelKey(
                r.get("occurredAt", OffsetDateTime.class).toInstant(),
                r.get("kind", Short.class),
                r.get("refId", String.class))).toList();
    }

    private static String highLevelTable(ViewKind v) {
        return switch (v) {
            case WEB -> "wqPrjWebHighLevel";
            case CC -> "wqPrjCcHighLevel";
        };
    }

    private static String highLevelWhere(HighLevelFilter f, Instant from, Instant to, List<Object> binds) {
        StringBuilder w = new StringBuilder("\"userId\" = ? and \"occurredAt\" >= ?::timestamptz and \"occurredAt\" < ?::timestamptz");
        binds.add(f.userId());
        binds.add(from.toString());
        binds.add(to.toString());
        if (f.excludeCancelled()) {
            w.append(" and \"visibleExclCancelled\"");
        }
        if (!f.productTypes().isEmpty()) {
            w.append(" and \"productType\" = any(?)");
            binds.add(f.productTypes().toArray(String[]::new));
        }
        return w.toString();
    }

    /** Same field rules as the view mappers: kind is the source's name, detail the raw JSON. */
    private static TransactionEntry entry(TestSql.Row r) {
        return new TransactionEntry(
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
