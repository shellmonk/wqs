package wqs.read.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.domain.ViewKind;
import wqs.read.HighLevelFilter;
import wqs.read.HighLevelKey;
import wqs.read.HighLevelQuery;

/**
 * Level-1/2 reads of the web view's page index (spec 5.6); touches only "wqWebHighLevel". Every
 * predicate and sort column is in "wqWebHighLevel_cover" -- key ("userId", "occurredAt" desc,
 * "kind" desc, "refId" desc), INCLUDE ("productType", "visibleExclCancelled") -- so both queries
 * are covering index scans.
 */
@Component
public class WebHighLevelQuery implements HighLevelQuery {

    private static final String COUNT = """
            select count(*) from "wqWebHighLevel"
            where %s
            """;

    private static final String KEYS = """
            select "wqWebHighLevel"."occurredAt", "wqWebHighLevel"."kind", "wqWebHighLevel"."refId"
            from "wqWebHighLevel"
            where %s
            order by "wqWebHighLevel"."occurredAt" desc, "wqWebHighLevel"."kind" desc, "wqWebHighLevel"."refId" desc
            offset :offset rows fetch next :limit rows only
            """;

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }

    @Override
    public long count(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi) {
        return range(tx.sql(COUNT.formatted(range(f))), f, lo, hi).query(Long.class).single();
    }

    @Override
    public List<HighLevelKey> keys(
            JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi, long offset, int limit) {
        return range(tx.sql(KEYS.formatted(range(f))), f, lo, hi)
                .param("offset", offset)
                .param("limit", limit)
                .query((rs, n) -> new HighLevelKey(rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
                        rs.getShort("kind"), rs.getString("refId")))
                .list();
    }

    /** The key-range predicate, value-free: the filter only decides which fixed fragments appear. */
    private static String range(HighLevelFilter f) {
        StringBuilder c = new StringBuilder("""
                ("wqWebHighLevel"."userId" = :userId and "wqWebHighLevel"."occurredAt" >= cast(:lo as timestamptz) \
                and "wqWebHighLevel"."occurredAt" < cast(:hi as timestamptz)""");
        if (f.excludeCancelled()) {
            c.append(" and \"wqWebHighLevel\".\"visibleExclCancelled\" = true");
        }
        if (!f.productTypes().isEmpty()) {
            c.append(" and \"wqWebHighLevel\".\"productType\" in (:productTypes)");
        }
        return c.append(')').toString();
    }

    private static JdbcClient.StatementSpec range(
            JdbcClient.StatementSpec spec, HighLevelFilter f, Instant lo, Instant hi) {
        spec = spec.param("userId", f.userId()).param("lo", utc(lo)).param("hi", utc(hi));
        if (!f.productTypes().isEmpty()) {
            spec = spec.param("productTypes", f.productTypes());
        }
        return spec;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
