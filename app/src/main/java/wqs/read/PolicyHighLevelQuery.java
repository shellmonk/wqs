package wqs.read;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Level-1/2 entry reads through one endpoint's {@link ReadPolicy} (plan D4): the owner's sessions,
 * by "startedAt", merged with their standalone transactions (no batch id, not a cancellation), newest
 * first in the total order (time desc, kind desc, refId desc) -- kind 0 and refId "batchId" for a
 * session, the source code and "sourceId" for a standalone row. There is no page-index table.
 *
 * <p>Each side reads its own index in exactly that order: "wqPrjSession_reading" ("ownerType", "userId",
 * "startedAt" desc, "batchId" desc) and the partial "wqPrjTransaction_standalone" ("ownerType", "userId",
 * "occurredAt" desc, "source" desc, "sourceId" desc) where "batchId" is null and "cancels" is null. The
 * key query takes at most offset + limit from each, ordered, and merges them. excludeCancelled reads a
 * session's "visibleExclCancelled" and a standalone row's "cancelled"; the product filter reads each
 * one's "productType". A session belongs to a policy iff its first transaction's source does
 * ("firstSource"), the source its ENTRY count is kept under. One subclass per endpoint.
 */
public abstract class PolicyHighLevelQuery implements HighLevelQuery {

    // %1$s: the session-side filter fragments, %2$s: the standalone side's -- fixed, never values.
    private static final String COUNT = """
            select (select count(*) from "wqPrjSession"
                    where ("wqPrjSession"."ownerType" = :ownerType and "wqPrjSession"."userId" = :userId
                           and "wqPrjSession"."firstSource" = any (cast(:sources as smallint[]))
                           and "wqPrjSession"."startedAt" >= cast(:lo as timestamptz)
                           and "wqPrjSession"."startedAt" < cast(:hi as timestamptz)%1$s))
                 + (select count(*) from "wqPrjTransaction"
                    where ("wqPrjTransaction"."ownerType" = :ownerType and "wqPrjTransaction"."userId" = :userId
                           and "wqPrjTransaction"."batchId" is null and "wqPrjTransaction"."cancels" is null
                           and "wqPrjTransaction"."source" = any (cast(:sources as smallint[]))
                           and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                           and "wqPrjTransaction"."occurredAt" >= cast(:lo as timestamptz)
                           and "wqPrjTransaction"."occurredAt" < cast(:hi as timestamptz)%2$s))
            """;

    private static final String KEYS = """
            select "entry"."occurredAt", "entry"."kind", "entry"."refId" from (
                (select "wqPrjSession"."startedAt" as "occurredAt", cast(0 as smallint) as "kind",
                        "wqPrjSession"."batchId" as "refId"
                 from "wqPrjSession"
                 where ("wqPrjSession"."ownerType" = :ownerType and "wqPrjSession"."userId" = :userId
                        and "wqPrjSession"."firstSource" = any (cast(:sources as smallint[]))
                        and "wqPrjSession"."startedAt" >= cast(:lo as timestamptz)
                        and "wqPrjSession"."startedAt" < cast(:hi as timestamptz)%1$s)
                 order by "wqPrjSession"."startedAt" desc, "wqPrjSession"."batchId" desc
                 limit :reach)
              union all
                (select "wqPrjTransaction"."occurredAt", "wqPrjTransaction"."source", "wqPrjTransaction"."sourceId"
                 from "wqPrjTransaction"
                 where ("wqPrjTransaction"."ownerType" = :ownerType and "wqPrjTransaction"."userId" = :userId
                        and "wqPrjTransaction"."batchId" is null and "wqPrjTransaction"."cancels" is null
                        and "wqPrjTransaction"."source" = any (cast(:sources as smallint[]))
                        and (cast(:includeZeroAmounts as boolean) or not "wqPrjTransaction"."zeroAmount")
                        and "wqPrjTransaction"."occurredAt" >= cast(:lo as timestamptz)
                        and "wqPrjTransaction"."occurredAt" < cast(:hi as timestamptz)%2$s)
                 order by "wqPrjTransaction"."occurredAt" desc, "wqPrjTransaction"."source" desc,
                          "wqPrjTransaction"."sourceId" desc
                 limit :reach)
            ) as "entry"
            order by "entry"."occurredAt" desc, "entry"."kind" desc, "entry"."refId" desc
            offset :offset rows fetch next :limit rows only
            """;

    private static final String SESSION_VISIBLE = " and \"wqPrjSession\".\"visibleExclCancelled\"";
    private static final String SESSION_PRODUCT_TYPES = " and \"wqPrjSession\".\"productType\" in (:productTypes)";
    private static final String STANDALONE_NOT_CANCELLED = " and not (\"wqPrjTransaction\".\"cancelled\")";
    private static final String STANDALONE_PRODUCT_TYPES =
            " and \"wqPrjTransaction\".\"productType\" in (:productTypes)";

    private final ReadPolicy policy;

    protected PolicyHighLevelQuery(ReadPolicy policy) {
        this.policy = policy;
    }

    @Override
    public final ReadPolicy policy() {
        return policy;
    }

    @Override
    public long count(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi) {
        return range(tx.sql(statement(COUNT, f)), f, lo, hi).query(Long.class).single();
    }

    @Override
    public List<HighLevelKey> keys(
            JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi, long offset, int limit) {
        return range(tx.sql(statement(KEYS, f)), f, lo, hi)
                .param("reach", offset + limit)
                .param("offset", offset)
                .param("limit", limit)
                .query((rs, n) -> new HighLevelKey(rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
                        rs.getShort("kind"), rs.getString("refId")))
                .list();
    }

    private static String statement(String sql, HighLevelFilter f) {
        StringBuilder sessions = new StringBuilder();
        StringBuilder standalone = new StringBuilder();
        if (f.excludeCancelled()) {
            sessions.append(SESSION_VISIBLE);
            standalone.append(STANDALONE_NOT_CANCELLED);
        }
        if (!f.productTypes().isEmpty()) {
            sessions.append(SESSION_PRODUCT_TYPES);
            standalone.append(STANDALONE_PRODUCT_TYPES);
        }
        return sql.formatted(sessions, standalone);
    }

    private JdbcClient.StatementSpec range(JdbcClient.StatementSpec spec, HighLevelFilter f, Instant lo, Instant hi) {
        spec = policy.bind(spec).param("userId", f.userId()).param("lo", utc(lo)).param("hi", utc(hi));
        if (!f.productTypes().isEmpty()) {
            spec = spec.param("productTypes", f.productTypes());
        }
        return spec;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
