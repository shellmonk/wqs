package wqs.projection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.db.TestSql;
import wqs.domain.MoneyDirection;
import wqs.domain.SourceKind;
import wqs.read.ReadPolicy;

/**
 * The one oracle for the projection's derived state (spec 8.1 P-2, P-4, P-8). Each method
 * recomputes what the incremental write path maintains -- owner-month counts, rollups and the
 * sessions' entry keys -- from the transaction rows alone, compares it with what is stored, and returns one
 * human-readable line per discrepancy: empty when the invariant holds.
 *
 * Deliberately slow-but-obvious SQL over the whole projection: these are the reference
 * implementations the fast paths are checked against, and Phase 7's reconciliation jobs reuse them
 * against production data, so CI and production check the same thing. Every comparison is a FULL
 * OUTER JOIN of expected against stored, so a stored row nothing justifies (orphan) and an
 * expected row nothing stores (missing) both surface, not only value mismatches.
 *
 * The spec semantics checked here, which the write path must also follow:
 * <ul>
 *   <li>A cancellation is a transaction row ("cancels" non-blank). It is counted at TX like any row,
 *       but never rolls up and is never a level-1/2 entry.</li>
 *   <li>A session's key is (MIN occurredAt, the FIRST transaction's productType) over its
 *       non-cancellation transactions, cancelled or not -- a cancel never moves a row. First is by
 *       ("occurredAt", "sourceId", "source") (plan D6); its productId is the same transaction's, so the
 *       pair always belongs to one transaction. A session is single-currency (mismatches are
 *       dead-lettered), so its currency is any transaction's -- MIN here.</li>
 *   <li>Every owner is ("ownerType", "userId"): every grouping and join below carries both.</li>
 *   <li>A zero-amount transaction ("zeroAmount") is a transaction row like any other, counted at
 *       TX under its own "zeroAmount" = true rows, but in no rollup sum and no group key; a standalone one
 *       is a zero-amount ENTRY; a session's and play's "zeroTxCount" counts them (plan D6), and a group
 *       exists only once it has a non-zero row. A zero-amount cancel cancels like any other.</li>
 *   <li>Counts are sparse: a stored row with "cnt" = 0 is itself drift.</li>
 *   <li>A cancel parked longer than the resolution threshold may be removed by pendingCancelResolution
 *       (plan D3): its target, absent or arriving later, is then legitimately uncancelled.</li>
 * </ul>
 */
public final class ProjectionInvariants {

    private static final String NOT_A_CANCELLATION = "(i.\"cancels\" is null or btrim(i.\"cancels\") = '')";

    /**
     * An endpoint's level-1/2 entries (plan D4) as one relation in the shape the page index had:
     * ("ownerType", "userId", "occurredAt", "kind", "refId", "productId", "currency", "productType",
     * "visibleExclCancelled") -- its sessions (kind 0, by "startedAt", in the policy iff their
     * "firstSource" is) merged with its standalone transactions (no batch id, not a cancellation, not
     * zero-amount; kind = source). Every owner type, like the stored rows: callers filter the owner.
     */
    public static String entries(ReadPolicy policy) {
        return entries(policy.sources().stream().map(SourceKind::code).toArray(Integer[]::new));
    }

    /** {@link #entries(ReadPolicy)} over source codes written out by hand (the naive oracle's). */
    public static String entries(Integer... sourceCodes) {
        String sources = java.util.Arrays.stream(sourceCodes).map(String::valueOf)
                .collect(Collectors.joining(", "));
        return """
                (select "ownerType", "userId", "startedAt" as "occurredAt", cast(0 as smallint) as "kind",
                        "batchId" as "refId", "productId", "currency", "productType", "visibleExclCancelled"
                 from "wqPrjSession" where "firstSource" in (%1$s)
                 union all
                 select "ownerType", "userId", "occurredAt", "source", "sourceId", "productId", "currency",
                        "productType", not "cancelled"
                 from "wqPrjTransaction"
                 where "batchId" is null and "cancels" is null and not "zeroAmount" and "source" in (%1$s))""".formatted(sources);
    }

    /** /web's entries; see {@link #entries}. */
    public static final String WEB_TIMELINE = entries(1, 3);

    /** /cc's entries; see {@link #entries}. */
    public static final String CC_TIMELINE = entries(1, 2, 3, 4, 5);

    /**
     * The transaction rows of /web's sources (what the dropped "wqPrjWebTransaction" view held), as a
     * relation; /cc's are every row of "wqPrjTransaction".
     */
    public static final String WEB_TRANSACTIONS =
            "(select * from \"wqPrjTransaction\" where \"source\" in (1, 3))";

    /** A table name double-quoted, or a parenthesised relation such as {@link #WEB_TIMELINE} as is. */
    public static String relation(String tableOrQuery) {
        return tableOrQuery.startsWith("(") ? tableOrQuery + " r" : "\"" + tableOrQuery + "\"";
    }

    /** The pendingCancelResolution default (application.yaml: thresholdDays 7). */
    public static final Duration RESOLUTION_THRESHOLD = Duration.ofDays(7);

    private final TestSql sql;

    private final Duration resolutionThreshold;

    public ProjectionInvariants(JdbcClient jdbc) {
        this(jdbc, RESOLUTION_THRESHOLD);
    }

    /** @param resolutionThreshold the pendingCancelResolution threshold the data was resolved with */
    public ProjectionInvariants(JdbcClient jdbc, Duration resolutionThreshold) {
        this.sql = new TestSql(jdbc);
        this.resolutionThreshold = resolutionThreshold;
    }

    /** Every invariant at once. */
    public List<String> drift() {
        List<String> all = new ArrayList<>();
        all.addAll(counterDrift());
        all.addAll(aggregationDrift());
        all.addAll(highLevelDrift());
        all.addAll(cancellationDrift());
        return all;
    }

    /**
     * Cancellation itself (spec 6.3), on "wqPrjTransaction": a transaction is "cancelled" exactly when
     * a live cancel row for it exists -- same source, same owner, "cancels" = its sourceId -- and then
     * "cancelledBy" is the least such cancel's sourceId (null otherwise). And "wqPrjPendingCancel"
     * holds exactly the cancels whose target is absent.
     *
     * <p>Resolution (plan D3): a cancel that waited for its target longer than the threshold may have
     * been removed by pendingCancelResolution, and then never applies. A cancel is parked in its own
     * insert's transaction, so its "parkedAt" is its row's "ingestedAt" (both now()). Such an EXPIRED
     * cancel has no parked row, was ingested at least the threshold ago, and its target is absent or
     * arrived at least the threshold after it (after the resolution). An expired cancel is in no
     * expected cancel; one ingested the threshold ago whose target is still absent may be parked or
     * resolved (the job may not have run yet).
     *
     * A drained cancel that failed and was dead-lettered (spec 6.3/6.4) legitimately reports here
     * as "cancel not applied": it is an incident awaiting replay, not a false positive.
     */
    public List<String> cancellationDrift() {
        List<String> drift = new ArrayList<>();
        String cancels = "c.\"cancels\" is not null and btrim(c.\"cancels\") <> ''";
        String threshold = "make_interval(secs => " + resolutionThreshold.toSeconds() + ")";
        // Every cancel is a transaction row, zero-amount ones included; "expired" per the class comment.
        String allCancels = """
                select c."source", c."ownerType", c."userId", c."sourceId", c."cancels" as target,
                       (c."ingestedAt" <= now() - %2$s
                        and not exists (select 1 from "wqPrjPendingCancel" p
                                        where p."source" = c."source" and p."cancelSourceId" = c."sourceId")
                        and not exists (select 1 from "wqPrjTransaction" t
                                        where t."source" = c."source" and t."sourceId" = c."cancels"
                                          and t."ownerType" = c."ownerType" and t."userId" = c."userId"
                                          and t."ingestedAt" < c."ingestedAt" + %2$s)) as expired,
                       c."ingestedAt" <= now() - %2$s as old
                from "wqPrjTransaction" c where %1$s
                """.formatted(cancels, threshold);
        drift.addAll(sql.rows("""
                with cancel as (
                    select "source", "ownerType", "userId", target, min("sourceId") as "by"
                    from (%1$s) all_cancels
                    where not expired
                    group by 1, 2, 3, 4
                )
                select case when i."cancelled" and k.target is null then 'cancelled without a cancel'
                            when not i."cancelled" and k.target is not null then 'cancel not applied'
                            else 'cancelledBy mismatch' end as problem,
                       i."ownerType", i."userId", i."source", i."sourceId",
                       i."cancelled" as "storedCancelled",
                       k."by" as "expectedCancelledBy", i."cancelledBy" as "storedCancelledBy"
                from "wqPrjTransaction" i
                left join cancel k
                  on k."source" = i."source" and k."ownerType" = i."ownerType" and k."userId" = i."userId"
                 and k.target = i."sourceId"
                where i."cancelled" <> (k.target is not null)
                   or i."cancelledBy" is distinct from k."by"
                order by 2, 3, 4, 5
                """.formatted(allCancels))
                .stream().map(ProjectionInvariants::describe).toList());
        drift.addAll(sql.rows("""
                with expected as (
                    select c."source", c."sourceId", c.target as "targetSourceId", c."ownerType", c."userId", c.old
                    from (%1$s) c
                    where not exists (select 1 from "wqPrjTransaction" l
                                      where l."source" = c."source" and l."sourceId" = c.target
                                        and l."ownerType" = c."ownerType" and l."userId" = c."userId")
                )
                select case when e."sourceId" is null then 'unexpected pending cancel'
                            else 'missing pending cancel' end as problem,
                       coalesce(e."ownerType", p."ownerType") as "ownerType",
                       coalesce(e."userId", p."userId") as "userId",
                       coalesce(e."source", p."source") as "source",
                       coalesce(e."sourceId", p."cancelSourceId") as "cancelSourceId",
                       coalesce(e."targetSourceId", p."targetSourceId") as "targetSourceId"
                from expected e
                full outer join "wqPrjPendingCancel" p
                  on p."source" = e."source" and p."cancelSourceId" = e."sourceId"
                 and p."targetSourceId" = e."targetSourceId" and p."ownerType" = e."ownerType" and p."userId" = e."userId"
                where (e."sourceId" is null or p."cancelSourceId" is null) and not coalesce(e.old, false)
                order by 2, 3, 4, 5
                """.formatted(allCancels))
                .stream().map(ProjectionInvariants::describe).toList());
        return drift;
    }

    /**
     * The first transaction of each session -- by ("occurredAt", "sourceId", "source") over its non-zero
     * non-cancellation rows, cancelled or not (plan D6) -- with the session's entry key and visibility:
     * ("ownerType", "userId", "batchId", "startedAt", "productId", "productType", "firstSourceId",
     * "firstSource", currency, visible). A session's entry is visible while any such row is not cancelled.
     */
    private static final String SESSION_KEY = """
            select i."ownerType", i."userId", i."batchId", min(i."occurredAt") as "startedAt",
                   (array_agg(i."productId" order by i."occurredAt", i."sourceId", i."source"))[1] as "productId",
                   (array_agg(i."productType" order by i."occurredAt", i."sourceId", i."source"))[1] as "productType",
                   (array_agg(i."sourceId" order by i."occurredAt", i."sourceId", i."source"))[1] as "firstSourceId",
                   (array_agg(i."source" order by i."occurredAt", i."sourceId", i."source"))[1] as "firstSource",
                   min(i."currency"::text) as currency,
                   bool_or(not i."cancelled") as visible
            from "wqPrjTransaction" i
            where i."batchId" is not null and (i."cancels" is null or btrim(i."cancels") = '') and not i."zeroAmount"
            group by 1, 2, 3
            """;

    /**
     * P-2: every "wqOwnerMonth" row equals a live COUNT, and no other row exists (plan D5).
     *
     * Expected rows, per owner, UTC month and exact combination: TX from every transaction row
     * (cancellations included; excl = not cancelled) at its (source, productType or '', type,
     * zeroAmount); ENTRY from every standalone transaction (no batch id, not a cancellation; zero-amount
     * ones under "zeroAmount") at its own (month, source, productType or '') and from every session at
     * its key derived from its transactions -- (month of the first transaction, its source, its
     * productType or '') -- with "type" '' and excl = visible.
     */
    public List<String> counterDrift() {
        return sql.rows("""
                with src as (
                    select "ownerType" as o, "userId", "occurredAt" as t, "source"::int as s, 0 as u,
                           coalesce("productType", '') as p, "type" as ty, "zeroAmount" as z, not "cancelled" as vis
                    from "wqPrjTransaction"
                  union all
                    select i."ownerType", i."userId", i."occurredAt", i."source"::int, 1,
                           coalesce(i."productType", ''), '', i."zeroAmount", not i."cancelled"
                    from "wqPrjTransaction" i
                    where i."batchId" is null and %1$s
                  union all
                    select k."ownerType", k."userId", k."startedAt", k."firstSource"::int, 1,
                           coalesce(k."productType", ''), '', false, k.visible
                    from (%2$s) k
                ),
                expected as (
                    select o, "userId", date_trunc('month', t at time zone 'UTC')::date as "month", s, u, p, ty, z,
                           count(*) as cnt, count(*) filter (where vis) as excl
                    from src group by 1, 2, 3, 4, 5, 6, 7, 8
                ),
                stored as (
                    select "ownerType" as o, "userId", "month", "source"::int as s, "unit"::int as u,
                           "productType" as p, "type" as ty, "zeroAmount" as z,
                           "cnt", "cntExclCancelled" as excl
                    from "wqOwnerMonth"
                )
                select case when e."userId" is null then 'orphan count row'
                            when s."userId" is null then 'missing count row'
                            else 'count mismatch' end as problem,
                       coalesce(e.o, s.o) as "ownerType",
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e."month", s."month") as "month",
                       coalesce(e.s, s.s) as "source",
                       case coalesce(e.u, s.u) when 0 then 'TX' else 'ENTRY' end as "unit",
                       coalesce(e.p, s.p) as "productType",
                       coalesce(e.ty, s.ty) as "type",
                       coalesce(e.z, s.z) as "zeroAmount",
                       e.cnt as "expectedCnt", s.cnt as "storedCnt",
                       e.excl as "expectedExcl", s.excl as "storedExcl"
                from expected e
                full outer join stored s
                  on s.o = e.o and s."userId" = e."userId" and s."month" = e."month" and s.s = e.s and s.u = e.u
                 and s.p = e.p and s.ty = e.ty and s.z = e.z
                where e."userId" is null or s."userId" is null
                   or e.cnt <> s.cnt or e.excl <> s.excl
                order by 2, 3, 4, 5, 6, 7, 8, 9
                """.formatted(NOT_A_CANCELLATION, SESSION_KEY))
                .stream().map(ProjectionInvariants::describe).toList();
    }

    /**
     * P-4: every session and play row, matched by its key ("userId", "batchId"[, "subBatchId"]),
     * equals a fold over its transactions.
     *
     * Expected from the non-cancellation sessioned rows of "wqPrjTransaction" (every source):
     * txCount and its ExclCancelled form; playCount = distinct non-null subBatchId,
     * playCountExclCancelled = plays with at least one non-cancelled transaction; totalPlays, totalWins
     * (abs(amount) by the type's {@link wqs.domain.TransactionType} direction, exactly as
     * {@link RollupUpdater}) and net
     * = wins - plays, each with its ExclCancelled form; startedAt = MIN occurredAt, endedAt = MAX,
     * productId and productType = the first transaction's pair by (occurredAt, sourceId, source),
     * currency = MIN (the session is single-currency). Orphan rows (no transactions) and missing rows are reported too.
     */
    public List<String> aggregationDrift() {
        String plays = money(MoneyDirection.PLAY);
        String wins = money(MoneyDirection.WIN);
        String sessioned = """
                select i."ownerType", i."userId", i."batchId", i."subBatchId", i."occurredAt", i."productId",
                       i."productType", i."currency"::text as currency, i."cancelled",
                       i."sourceId", i."source",
                       %s as plays, %s as wins
                from "wqPrjTransaction" i
                where i."batchId" is not null and %s and not i."zeroAmount"
                """.formatted(plays, wins, NOT_A_CANCELLATION);
        // A group's zero-amount rows: only its "zeroTxCount" (plan D6).
        String zeros = """
                select i."ownerType", i."userId", i."batchId", i."subBatchId"
                from "wqPrjTransaction" i
                where i."batchId" is not null and %s and i."zeroAmount"
                """.formatted(NOT_A_CANCELLATION);
        String folded = """
                count(*)::int as "txCount",
                count(*) filter (where not "cancelled")::int as "txCountExclCancelled",
                sum(plays) as "totalPlays", sum(wins) as "totalWins",
                sum(wins) - sum(plays) as "net",
                coalesce(sum(plays) filter (where not "cancelled"), 0) as "totalPlaysExclCancelled",
                coalesce(sum(wins) filter (where not "cancelled"), 0) as "totalWinsExclCancelled",
                coalesce(sum(wins) filter (where not "cancelled"), 0)
                  - coalesce(sum(plays) filter (where not "cancelled"), 0) as "netExclCancelled",
                min("occurredAt") as "startedAt", max("occurredAt") as "endedAt",
                (array_agg("productId" order by "occurredAt", "sourceId", "source"))[1] as "productId",
                (array_agg("productType" order by "occurredAt", "sourceId", "source"))[1] as "productType",
                min(currency) as currency
                """;
        String compared = """
                e."txCount" is distinct from s."txCount"
                or e."txCountExclCancelled" is distinct from s."txCountExclCancelled"
                or e."totalPlays" is distinct from s."totalPlays"
                or e."totalWins" is distinct from s."totalWins"
                or e."net" is distinct from s."net"
                or e."totalPlaysExclCancelled" is distinct from s."totalPlaysExclCancelled"
                or e."totalWinsExclCancelled" is distinct from s."totalWinsExclCancelled"
                or e."netExclCancelled" is distinct from s."netExclCancelled"
                or e."startedAt" is distinct from s."startedAt"
                or e."endedAt" is distinct from s."endedAt"
                or e."productId" is distinct from s."productId"
                or e."productType" is distinct from s."productType"
                or e.currency is distinct from s.currency::text
                or coalesce(z.n, 0) is distinct from s."zeroTxCount"
                """;

        List<String> drift = new ArrayList<>();
        drift.addAll(sql.rows("""
                with transactions as (%1$s),
                expected as (
                    select "ownerType", "userId", "batchId", %2$s,
                           count(distinct "subBatchId")::int as "playCount",
                           count(distinct "subBatchId") filter (where not "cancelled")::int
                             as "playCountExclCancelled"
                    from transactions group by 1, 2, 3
                ),
                z as (select "ownerType", "userId", "batchId", count(*)::int as n from (%4$s) zr group by 1, 2, 3)
                select case when e."batchId" is null then 'orphan session row'
                            when s."batchId" is null then 'missing session row'
                            else 'session mismatch' end as problem,
                       coalesce(e."ownerType", s."ownerType") as "ownerType",
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e."batchId", s."batchId") as "batchId",
                       e::text as expected, s::text as stored, z.n as "expectedZeroTxCount"
                from expected e
                full outer join "wqPrjSession" s
                  on s."ownerType" = e."ownerType" and s."userId" = e."userId" and s."batchId" = e."batchId"
                left join z on z."ownerType" = s."ownerType" and z."userId" = s."userId" and z."batchId" = s."batchId"
                where e."batchId" is null or s."batchId" is null
                   or %3$s
                   or e."playCount" is distinct from s."playCount"
                   or e."playCountExclCancelled" is distinct from s."playCountExclCancelled"
                order by 2, 3, 4
                """.formatted(sessioned, folded, compared, zeros))
                .stream().map(ProjectionInvariants::describe).toList());
        drift.addAll(sql.rows("""
                with transactions as (%1$s),
                expected as (
                    select "ownerType", "userId", "batchId", "subBatchId", %2$s
                    from transactions where "subBatchId" is not null group by 1, 2, 3, 4
                ),
                z as (select "ownerType", "userId", "batchId", "subBatchId", count(*)::int as n
                      from (%4$s) zr where "subBatchId" is not null group by 1, 2, 3, 4)
                select case when e."batchId" is null then 'orphan play row'
                            when s."batchId" is null then 'missing play row'
                            else 'play mismatch' end as problem,
                       coalesce(e."ownerType", s."ownerType") as "ownerType",
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e."batchId", s."batchId") as "batchId",
                       coalesce(e."subBatchId", s."subBatchId") as "subBatchId",
                       e::text as expected, s::text as stored, z.n as "expectedZeroTxCount"
                from expected e
                full outer join "wqPrjPlay" s
                  on s."ownerType" = e."ownerType" and s."userId" = e."userId" and s."batchId" = e."batchId"
                 and s."subBatchId" = e."subBatchId"
                left join z on z."ownerType" = s."ownerType" and z."userId" = s."userId"
                 and z."batchId" = s."batchId" and z."subBatchId" = s."subBatchId"
                where e."batchId" is null or s."batchId" is null or %3$s
                order by 2, 3, 4, 5
                """.formatted(sessioned, folded, compared, zeros))
                .stream().map(ProjectionInvariants::describe).toList());
        // Final-review I1: a zero-amount count waits in "wqPrjPendingZero" only while its group does not
        // exist; the group's first non-zero row takes it. A waiting count beside its group is lost.
        drift.addAll(sql.rows("""
                select 'pending zero beside its group' as problem, p."ownerType", p."userId", p."batchId",
                       p."subBatchId", p."sessionCount", p."playCount"
                from "wqPrjPendingZero" p
                where (p."sessionCount" > 0 and exists (select 1 from "wqPrjSession" s
                          where s."ownerType" = p."ownerType" and s."userId" = p."userId" and s."batchId" = p."batchId"))
                   or (p."playCount" > 0 and exists (select 1 from "wqPrjPlay" y
                          where y."ownerType" = p."ownerType" and y."userId" = p."userId" and y."batchId" = p."batchId"
                            and y."subBatchId" = p."subBatchId"))
                   or (p."sessionCount" = 0 and p."playCount" = 0)
                order by 2, 3, 4, 5
                """).stream().map(ProjectionInvariants::describe).toList());
        return drift;
    }

    /**
     * P-8: each session's level-1/2 entry key and visibility (plan D4). Levels 1-2 merge the sessions
     * with the standalone transaction rows themselves, so a session row is the only derived entry:
     * its "startedAt", "productId" and "productType" (also checked by {@link #aggregationDrift}), its
     * "firstSourceId" and "firstSource" -- the first transaction's identity, which decides the policies
     * the session is shown in -- and its "visibleExclCancelled" must equal what its transactions justify.
     * Missing and orphan session rows are reported by {@link #aggregationDrift}.
     */
    public List<String> highLevelDrift() {
        return sql.rows("""
                with k as (%s)
                select 'session entry mismatch' as problem,
                       s."ownerType", s."userId", s."batchId",
                       k."startedAt" as "expectedAt", s."startedAt" as "storedAt",
                       k."firstSourceId" as "expectedFirstSourceId", s."firstSourceId" as "storedFirstSourceId",
                       k."firstSource" as "expectedFirstSource", s."firstSource" as "storedFirstSource",
                       k."productId" as "expectedProductId", s."productId" as "storedProductId",
                       k."productType" as "expectedProductType", s."productType" as "storedProductType",
                       k.visible as "expectedVisible", s."visibleExclCancelled" as "storedVisible"
                from "wqPrjSession" s
                join k on k."ownerType" = s."ownerType" and k."userId" = s."userId" and k."batchId" = s."batchId"
                where k."startedAt" <> s."startedAt"
                   or k."firstSourceId" is distinct from s."firstSourceId"
                   or k."firstSource" is distinct from s."firstSource"
                   or k."productId" is distinct from s."productId"
                   or k."productType" is distinct from s."productType"
                   or k.visible <> s."visibleExclCancelled"
                order by 2, 3, 4
                """.formatted(SESSION_KEY))
                .stream().map(ProjectionInvariants::describe).toList();
    }

    /**
     * P-7 (spec 7.4), the stored half: every non-cancellation transaction whose product type is eInstant
     * carries a non-null "batchId" in "wqPrjTransaction", and so no endpoint has a standalone eInstant
     * entry. {@code eInstantProductTypes} is {@code wqs.einstant.product-types}
     * (plan D3); the classification is configuration, which is why this cannot be a check
     * constraint. Cancellations are exempt, as at ingest (IngestRules): they are never an entry.
     */
    public List<String> eInstantDrift(java.util.Collection<String> eInstantProductTypes) {
        List<String> drift = new ArrayList<>();
        if (eInstantProductTypes.isEmpty()) {
            return drift;
        }
        String[] types = eInstantProductTypes.toArray(String[]::new);
        drift.addAll(sql.rows("""
                select 'unsessioned eInstant transaction' as problem,
                       i."userId", i."source", i."sourceId", i."productType"
                from "wqPrjTransaction" i
                where i."batchId" is null and i."productType" = any(?::text[]) and %s and not i."zeroAmount"
                order by 2, 3, 4
                """.formatted(NOT_A_CANCELLATION), (Object) types)
                .stream().map(ProjectionInvariants::describe).toList());
        return drift;
    }

    /**
     * abs(amount) for the transaction types whose {@link wqs.domain.TransactionType} direction is
     * {@code direction}, else 0. The codes come from the same enum the write path reads, so this
     * can never disagree with the write path's mapping; an unknown code is in no list (NEUTRAL).
     */
    private static String money(MoneyDirection direction) {
        String codes = java.util.Arrays.stream(wqs.domain.TransactionType.values())
                .filter(t -> t.direction() == direction)
                .map(t -> "'" + t.code() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return "case when i.\"type\" in (" + codes + ") then abs(i.\"amount\") else 0 end";
    }

    private static String describe(TestSql.Row r) {
        StringBuilder line = new StringBuilder(String.valueOf(r.get("problem")));
        r.asMap().entrySet().stream().skip(1).forEach(column -> {
            Object value = column.getValue();
            if (value instanceof java.time.OffsetDateTime at) {
                value = at.toInstant(); // UTC, whatever the session time zone
            }
            line.append(' ').append(column.getKey()).append('=').append(value);
        });
        return line.toString();
    }
}
