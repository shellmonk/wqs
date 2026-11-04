package wqs.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.entity.SessionEntity;

/** {@code "wqPrjSession"}: totals per session, updated as each of its rows arrives. */
public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findByOwnerIdAndBatchId(String ownerId, String batchId);

    /**
     * A top-level entry of a level-1/2 page: {@code kind} 0 is a session, 1 a transaction without
     * one; {@code at} its time (the session's start, or the transaction's occurredAt).
     */
    interface PageEntry {
        Integer getKind();

        Long getId();

        Instant getAt();
    }

    String ENTRIES = """
            select 0 as "kind", s."sessionId" as "id", s."startedAt" as "at"
            from "wqPrjSession" s
            where s."ownerId" = :ownerId
              and s."startedAt" >= :from and s."startedAt" < :to
              and (not :excludeCancelled or s."txCountExclCancelled" > 0)
              and (:anyProductType or s."productType" = any(cast(:productTypes as text[])))
              and (:anyType or s."batchId" in (
                    -- One pass over the owner's matching rows (a session's rows are never before its
                    -- start, so from bounds them), instead of one probe per session.
                    select m."batchId" from "wqPrjTransaction" m
                    where m."ownerId" = :ownerId and m."batchId" is not null
                      and m."occurredAt" >= :from
                      and m."source" = any(cast(:sources as int[])) and not m."zeroAmount"
                      and (not :excludeCancelled or not m."cancelled")
                      and m."type" = any(cast(:transactionTypes as text[]))))
            union all
            select 1, t."transactionId", t."occurredAt"
            from "wqPrjTransaction" t
            where t."ownerId" = :ownerId and t."batchId" is null
              and t."occurredAt" >= :from and t."occurredAt" < :to
              and t."source" = any(cast(:sources as int[])) and not t."zeroAmount"
              and (not :excludeCancelled or not t."cancelled")
              and (:anyProductType or t."productType" = any(cast(:productTypes as text[])))
              and (:anyType or t."type" = any(cast(:transactionTypes as text[])))
            """;

    /**
     * One page of level-1/2 entries: the owner's sessions (by start) merged with their transactions
     * that have no session, newest first; at the same instant a transaction comes before a session.
     * A session's totals are stored, so a filter only decides whether it is listed: it must have a
     * visible row of a requested type. Empty filter arrays mean "any" (the flags say so).
     */
    @Query(nativeQuery = true, value = "select e.\"kind\", e.\"id\", e.\"at\" from (" + ENTRIES + ") e "
            + "order by e.\"at\" desc, e.\"kind\" desc, e.\"id\" desc limit :limit offset :offset")
    List<PageEntry> findPageEntries(@Param("ownerId") String ownerId,
                                    @Param("from") Instant from, @Param("to") Instant to,
                                    @Param("sources") Integer[] sources,
                                    @Param("excludeCancelled") boolean excludeCancelled,
                                    @Param("anyProductType") boolean anyProductType,
                                    @Param("productTypes") String[] productTypes,
                                    @Param("anyType") boolean anyType,
                                    @Param("transactionTypes") String[] transactionTypes,
                                    @Param("limit") int limit, @Param("offset") long offset);

    /** The number of entries {@link #findPageEntries} pages through: a plain COUNT(*). */
    @Query(nativeQuery = true, value = "select count(*) from (" + ENTRIES + ") e")
    long countPageEntries(@Param("ownerId") String ownerId,
                          @Param("from") Instant from, @Param("to") Instant to,
                          @Param("sources") Integer[] sources,
                          @Param("excludeCancelled") boolean excludeCancelled,
                          @Param("anyProductType") boolean anyProductType,
                          @Param("productTypes") String[] productTypes,
                          @Param("anyType") boolean anyType,
                          @Param("transactionTypes") String[] transactionTypes);

    /**
     * Adds one row to its session, creating the session on its first row, and returns the
     * session's id. Empty when the session already has another currency: the currency check sits in
     * the upsert's {@code where}, so nothing changes and there is no read-then-write race. A row
     * earlier than the session's start moves the start and takes over the product fields.
     *
     * <p>Not {@code @Modifying}: the upsert returns the session's id ({@code returning}), which a
     * modifying query cannot; it runs as a query inside the caller's transaction.
     */
    @Query(nativeQuery = true, value = """
            insert into "wqPrjSession" as s (
                "ownerId", "batchId", "startedAt", "endedAt", "productId", "productType", "currency",
                "txCount", "totalPlays", "totalWins", "net",
                "txCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
            values (
                :ownerId, :batchId, :at, :at, :productId, :productType, :currency,
                1, :plays, :wins, :net,
                1, :plays, :wins, :net)
            on conflict ("ownerId", "batchId") do update set
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
    Optional<Long> addRow(@Param("ownerId") String ownerId,
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
