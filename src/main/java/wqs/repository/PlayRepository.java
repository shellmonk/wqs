package wqs.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.entity.PlayEntity;

/** {@code "wqPrjPlay"}: totals per play, updated as each of its rows arrives. */
public interface PlayRepository extends JpaRepository<PlayEntity, Long> {

    /** The outcome of adding a row to its play; {@code created} is true when the row made the play. */
    interface Added {
        Long getPlayId();

        Boolean getCreated();
    }

    Optional<PlayEntity> findByOwnerTypeAndUserIdAndBatchIdAndSubBatchId(short ownerType, long userId,
                                                                         String batchId, String subBatchId);

    /**
     * Adds one row to its play, creating the play on its first row. Empty when the play already
     * has another currency. Same rules as {@link SessionRepository#addRow}. {@code xmax = 0} is true
     * only for a row this statement inserted, which tells a new play from an existing one.
     */
    @Query(nativeQuery = true, value = """
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
            returning "playId" as "playId", (xmax = 0) as "created"
            """)
    Optional<Added> addRow(@Param("sessionId") long sessionId, @Param("ownerType") short ownerType,
                           @Param("userId") long userId, @Param("batchId") String batchId,
                           @Param("subBatchId") String subBatchId, @Param("at") Instant at,
                           @Param("productId") String productId, @Param("productType") String productType,
                           @Param("currency") String currency, @Param("plays") BigDecimal plays,
                           @Param("wins") BigDecimal wins, @Param("net") BigDecimal net);
}
