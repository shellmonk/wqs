package wqs.read.cc;

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
 * Level-1/2 reads of the cc view's page index (spec 5.6); touches only "wqPrjCcHighLevel". Every
 * predicate and sort column is in "wqPrjCcHighLevel_cover" -- key ("userId", "occurredAt" desc,
 * "kind" desc, "refId" desc), INCLUDE ("productType", "visibleExclCancelled") -- so both queries
 * are covering index scans.
 */
@Component
public class CcHighLevelQuery implements HighLevelQuery {

    private static final String COUNT = """
            select count(*) from "wqPrjCcHighLevel"
            where %s
            """;

    private static final String KEYS = """
            select "wqPrjCcHighLevel"."occurredAt", "wqPrjCcHighLevel"."kind", "wqPrjCcHighLevel"."refId"
            from "wqPrjCcHighLevel"
            where %s
            order by "wqPrjCcHighLevel"."occurredAt" desc, "wqPrjCcHighLevel"."kind" desc, "wqPrjCcHighLevel"."refId" desc
            offset :offset rows fetch next :limit rows only
            """;

    @Override
    public ViewKind view() {
        return ViewKind.CC;
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
                ("wqPrjCcHighLevel"."userId" = :userId and "wqPrjCcHighLevel"."occurredAt" >= cast(:lo as timestamptz) \
                and "wqPrjCcHighLevel"."occurredAt" < cast(:hi as timestamptz)""");
        if (f.excludeCancelled()) {
            c.append(" and \"wqPrjCcHighLevel\".\"visibleExclCancelled\" = true");
        }
        if (!f.productTypes().isEmpty()) {
            c.append(" and \"wqPrjCcHighLevel\".\"productType\" in (:productTypes)");
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
