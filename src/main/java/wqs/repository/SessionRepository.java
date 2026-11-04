package wqs.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.entity.SessionEntity;

/** {@code "wqPrjSession"}: totals per session, updated as each of its rows arrives. */
public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findByOwnerTypeAndUserIdAndBatchId(short ownerType, long userId, String batchId);

    /**
     * Adds one row to its session, creating the session on its first row, and returns the
     * session's id. Empty when the session already has another currency: the currency check sits in
     * the upsert's {@code where}, so nothing changes and there is no read-then-write race. A row
     * earlier than the session's start moves the start and takes over the product fields.
     */
    @Query(nativeQuery = true, value = """
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
    Optional<Long> addRow(@Param("ownerType") short ownerType, @Param("userId") long userId,
                          @Param("batchId") String batchId, @Param("at") Instant at,
                          @Param("productId") String productId, @Param("productType") String productType,
                          @Param("currency") String currency, @Param("plays") BigDecimal plays,
                          @Param("wins") BigDecimal wins, @Param("net") BigDecimal net);

    /** Counts a play that has just been created in this session. */
    @Modifying
    @Query(nativeQuery = true, value = """
            update "wqPrjSession"
            set "playCount" = "playCount" + 1, "playCountExclCancelled" = "playCountExclCancelled" + 1
            where "sessionId" = :sessionId
            """)
    int addPlay(@Param("sessionId") long sessionId);
}
