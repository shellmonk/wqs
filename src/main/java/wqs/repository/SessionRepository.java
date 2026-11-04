package wqs.repository;

import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.Contribution;

/** {@code "wqPrjSession"}: totals per session, updated as each of its rows arrives. */
@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds one row to its session, creating the session on its first row, and returns the
     * session's id. Empty when the session already has another currency (nothing is changed).
     *
     * <p>A row earlier than the session's start moves the start and takes over the product fields,
     * so the session always carries its earliest row's product.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> add(Contribution c) {
        return jdbc.sql("""
                insert into "wqPrjSession" as s (
                    "ownerType", "userId", "batchId", "startedAt", "endedAt", "productId", "productType", "currency",
                    "txCount", "totalPlays", "totalWins", "net",
                    "txCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
                values (
                    :ownerType, :userId, :batchId, :at, :at, :productId, :productType, :currency,
                    1, :plays, :wins, :net,
                    1, :plays, :wins, :net)
                on conflict ("ownerType", "userId", "batchId") do update set
                    "startedAt"   = least(s."startedAt", excluded."startedAt"),
                    "endedAt"     = greatest(s."endedAt", excluded."endedAt"),
                    "productId"   = case when excluded."startedAt" < s."startedAt" then excluded."productId" else s."productId" end,
                    "productType" = case when excluded."startedAt" < s."startedAt" then excluded."productType" else s."productType" end,
                    "txCount"     = s."txCount" + 1,
                    "totalPlays"  = s."totalPlays" + excluded."totalPlays",
                    "totalWins"   = s."totalWins" + excluded."totalWins",
                    "net"         = s."net" + excluded."net",
                    "txCountExclCancelled"    = s."txCountExclCancelled" + 1,
                    "totalPlaysExclCancelled" = s."totalPlaysExclCancelled" + excluded."totalPlays",
                    "totalWinsExclCancelled"  = s."totalWinsExclCancelled" + excluded."totalWins",
                    "netExclCancelled"        = s."netExclCancelled" + excluded."net"
                where s."currency" = excluded."currency"
                returning "sessionId"
                """)
                .param("ownerType", c.ownerType())
                .param("userId", c.userId())
                .param("batchId", c.batchId())
                .param("at", c.occurredAt().atOffset(ZoneOffset.UTC))
                .param("productId", c.productId())
                .param("productType", c.productType())
                .param("currency", c.currency())
                .param("plays", c.plays())
                .param("wins", c.wins())
                .param("net", c.net())
                .query(Long.class)
                .optional();
    }

    /** Counts a play that has just been created in this session. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addPlay(long sessionId) {
        jdbc.sql("""
                update "wqPrjSession"
                set "playCount" = "playCount" + 1, "playCountExclCancelled" = "playCountExclCancelled" + 1
                where "sessionId" = :sessionId
                """)
                .param("sessionId", sessionId)
                .update();
    }
}
