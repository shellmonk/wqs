package wqs.repository;

import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.Contribution;

/** {@code "wqPrjPlay"}: totals per play, updated as each of its rows arrives. */
@Repository
public class PlayRepository {

    /** The outcome of adding a row to its play. */
    public record Added(long playId, boolean created) {}

    private final JdbcClient jdbc;

    public PlayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds one row to its play, creating the play on its first row. Empty when the play already
     * has another currency (nothing is changed). Same rules as {@link SessionRepository#add}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Added> add(long sessionId, Contribution c) {
        return jdbc.sql("""
                insert into "wqPrjPlay" as p (
                    "sessionId", "ownerType", "userId", "batchId", "subBatchId", "startedAt", "endedAt",
                    "productId", "productType", "currency",
                    "txCount", "totalPlays", "totalWins", "net",
                    "txCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
                values (
                    :sessionId, :ownerType, :userId, :batchId, :subBatchId, :at, :at,
                    :productId, :productType, :currency,
                    1, :plays, :wins, :net,
                    1, :plays, :wins, :net)
                on conflict ("ownerType", "userId", "batchId", "subBatchId") do update set
                    "startedAt"   = least(p."startedAt", excluded."startedAt"),
                    "endedAt"     = greatest(p."endedAt", excluded."endedAt"),
                    "productId"   = case when excluded."startedAt" < p."startedAt" then excluded."productId" else p."productId" end,
                    "productType" = case when excluded."startedAt" < p."startedAt" then excluded."productType" else p."productType" end,
                    "txCount"     = p."txCount" + 1,
                    "totalPlays"  = p."totalPlays" + excluded."totalPlays",
                    "totalWins"   = p."totalWins" + excluded."totalWins",
                    "net"         = p."net" + excluded."net",
                    "txCountExclCancelled"    = p."txCountExclCancelled" + 1,
                    "totalPlaysExclCancelled" = p."totalPlaysExclCancelled" + excluded."totalPlays",
                    "totalWinsExclCancelled"  = p."totalWinsExclCancelled" + excluded."totalWins",
                    "netExclCancelled"        = p."netExclCancelled" + excluded."net"
                where p."currency" = excluded."currency"
                returning "playId", (xmax = 0) as "created"
                """)
                .param("sessionId", sessionId)
                .param("ownerType", c.ownerType())
                .param("userId", c.userId())
                .param("batchId", c.batchId())
                .param("subBatchId", c.subBatchId())
                .param("at", c.occurredAt().atOffset(ZoneOffset.UTC))
                .param("productId", c.productId())
                .param("productType", c.productType())
                .param("currency", c.currency())
                .param("plays", c.plays())
                .param("wins", c.wins())
                .param("net", c.net())
                .query((rs, row) -> new Added(rs.getLong("playId"), rs.getBoolean("created")))
                .optional();
    }
}
