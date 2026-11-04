package wqs.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.dto.TotalsIncrement;
import wqs.entity.PlayEntity;

/** {@code "wqPrjPlays"}: totals per play, updated as each of its rows arrives. */
public interface PlayRepository extends JpaRepository<PlayEntity, Long> {

    /** The outcome of adding a row to its play; {@code created} is true when the row made the play. */
    interface Added {
        Long getPlayId();

        Boolean getCreated();
    }

    List<PlayEntity> findBySessionIdInOrderByStartedAtDescPlayIdDesc(Collection<Long> sessionIds);

    Optional<PlayEntity> findByOwnerIdAndBatchIdAndSubBatchId(String ownerId,
                                                                         String batchId, String subBatchId);

    /**
     * Adds one row to its play, creating the play on its first row. Empty when the play already
     * has another currency. Same rules as {@link SessionRepository#addRow}. {@code xmax = 0} is true
     * only for a row this statement inserted, which tells a new play from an existing one.
     */
    @Query(nativeQuery = true, value = """
            insert into "wqPrjPlays" as p (
                "sessionId", "ownerId", "batchId", "subBatchId", "startedAt", "endedAt",
                "productId", "productType", "currency",
                "txCount", "totalPlays", "totalWins", "net",
                "txCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
            values (
                :#{#sessionId}, :#{#c.ownerId()}, :#{#c.batchId()}, :#{#c.subBatchId()}, :#{#c.occurredAt()}, :#{#c.occurredAt()},
                :#{#c.productId()}, :#{#c.productType()}, :#{#c.currency()},
                1, :#{#c.plays()}, :#{#c.wins()}, :#{#c.net()},
                1, :#{#c.plays()}, :#{#c.wins()}, :#{#c.net()})
            on conflict ("ownerId", "batchId", "subBatchId") do update set
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
    Optional<Added> addRow(@Param("sessionId") long sessionId, @Param("c") TotalsIncrement c);
}
