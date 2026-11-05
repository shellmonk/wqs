package wqs.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.dto.TotalsIncrement;
import wqs.entity.SessionEntity;

/** {@code "wqPrjSessions"}: totals per session, updated as each of its rows arrives. */
public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findByOwnerIdAndBatchId(String ownerId, String batchId);

    /**
     * A top-level entry of a level-1/2 page: a session, or a transaction without one. {@code id} is
     * the session's or the transaction's id, {@code at} its time (the session's start, or the
     * transaction's created). {@code kind} is {@link #SESSION} or {@link #TRANSACTION}, as the
     * {@link #ENTRIES} query writes it.
     */
    interface PageEntry {
        int SESSION = 0;
        int TRANSACTION = 1;

        Integer getKind();

        Long getId();

        Instant getAt();

        default boolean isSession() {
            return getKind() == SESSION;
        }
    }

    /**
     * What a level-1/2 page lists: the owner's entries in [from, to) that the policy's sources show.
     * An empty {@code productTypes} or {@code transactionTypes} means any.
     */
    record EntryFilter(String ownerId, Instant from, Instant to, Integer[] sources, boolean excludeCancelled,
                       String[] productTypes, String[] transactionTypes) {}

    String ENTRIES = """
            select 0 as "kind", s."sessionId" as "id", s."startedAt" as "at"
            from "wqPrjSessions" s
            where s."ownerId" = :#{#f.ownerId()}
              and s."startedAt" >= :#{#f.from()} and s."startedAt" < :#{#f.to()}
              and (not :#{#f.excludeCancelled()} or s."txCountExclCancelled" > 0)
              and (cardinality(cast(:#{#f.productTypes()} as text[])) = 0 or s."productType" = any(cast(:#{#f.productTypes()} as text[])))
              and (cardinality(cast(:#{#f.transactionTypes()} as text[])) = 0 or s."batchId" in (
                    -- One pass over the owner's matching rows (a session's rows are never before its
                    -- start, so from bounds them), instead of one probe per session.
                    select m."batchId" from "wqPrjTransactions" m
                    where m."ownerId" = :#{#f.ownerId()} and m."batchId" is not null
                      and m."created" >= :#{#f.from()}
                      and m."source" = any(cast(:#{#f.sources()} as int[])) and not m."zeroAmount"
                      and (not :#{#f.excludeCancelled()} or not m."cancelled")
                      and m."transactionType" = any(cast(:#{#f.transactionTypes()} as text[]))))
            union all
            select 1, t."transactionId", t."created"
            from "wqPrjTransactions" t
            where t."ownerId" = :#{#f.ownerId()} and t."batchId" is null
              and t."created" >= :#{#f.from()} and t."created" < :#{#f.to()}
              and t."source" = any(cast(:#{#f.sources()} as int[])) and not t."zeroAmount"
              and (not :#{#f.excludeCancelled()} or not t."cancelled")
              and (cardinality(cast(:#{#f.productTypes()} as text[])) = 0 or t."productType" = any(cast(:#{#f.productTypes()} as text[])))
              and (cardinality(cast(:#{#f.transactionTypes()} as text[])) = 0 or t."transactionType" = any(cast(:#{#f.transactionTypes()} as text[])))
            """;

    /**
     * One page of level-1/2 entries: the owner's sessions (by start) merged with their transactions
     * that have no session, newest first; at the same instant a transaction comes before a session.
     * A session's totals are stored, so a filter only decides whether it is listed: it must have a
     * visible row of a requested type.
     */
    @Query(nativeQuery = true, value = "select e.\"kind\", e.\"id\", e.\"at\" from (" + ENTRIES + ") e "
            + "order by e.\"at\" desc, e.\"kind\" desc, e.\"id\" desc limit :limit offset :offset")
    List<PageEntry> findPageEntries(@Param("f") EntryFilter filter, @Param("limit") int limit, @Param("offset") long offset);

    /** The number of entries {@link #findPageEntries} pages through: a plain COUNT(*). */
    @Query(nativeQuery = true, value = "select count(*) from (" + ENTRIES + ") e")
    long countPageEntries(@Param("f") EntryFilter filter);

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
            insert into "wqPrjSessions" as s (
                "ownerId", "batchId", "startedAt", "endedAt", "productId", "productType", "currency",
                "txCount", "totalPlays", "totalWins", "net",
                "txCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
            values (
                :#{#c.ownerId()}, :#{#c.batchId()}, :#{#c.created()}, :#{#c.created()}, :#{#c.productId()}, :#{#c.productType()}, :#{#c.currency()},
                1, :#{#c.plays()}, :#{#c.wins()}, :#{#c.net()},
                1, :#{#c.plays()}, :#{#c.wins()}, :#{#c.net()})
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
    Optional<Long> addRow(@Param("c") TotalsIncrement c);

    /** Counts a play that has just been created in this session. */
    @Modifying
    @Query(nativeQuery = true, value = """
            update "wqPrjSessions"
            set "playCount" = "playCount" + 1, "playCountExclCancelled" = "playCountExclCancelled" + 1
            where "sessionId" = :sessionId
            """)
    int addPlay(@Param("sessionId") long sessionId);
}
