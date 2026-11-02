package wqs.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import wqs.domain.MoneyDirection;

/**
 * The one oracle for the projection's derived state (spec 8.1 P-2, P-4, P-8). Each method
 * recomputes what the incremental write path maintains -- counters, rollups and group locators,
 * the page index -- from the item rows alone, compares it with what is stored, and returns one
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
 *   <li>A cancellation is an item row ("cancels" non-blank). It is counted at the ITEM page unit
 *       like any row, but never rolls up and never gets a page-index row.</li>
 *   <li>A session's key is (MIN occurredAt, LEAST non-null productId) over its non-cancellation
 *       items, cancelled or not -- a cancel never moves a row. Its currency is LEAST likewise.</li>
 *   <li>Counters are sparse: a stored row with cntAll = 0 is itself drift.</li>
 * </ul>
 */
public final class ProjectionInvariants {

    private static final String NOT_A_CANCELLATION = "(i.\"cancels\" is null or btrim(i.\"cancels\") = '')";

    private final DSLContext dsl;

    public ProjectionInvariants(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Every invariant at once. */
    public List<String> drift() {
        List<String> all = new ArrayList<>();
        all.addAll(counterDrift());
        all.addAll(aggregationDrift());
        all.addAll(highLevelDrift());
        return all;
    }

    /**
     * P-2: every counter row in both tiers equals a live COUNT, and no other counter row exists.
     *
     * Expected rows, per view, page unit, tier, product key and UTC period (day, or first of the
     * month): ITEM from the view's item rows (cancellation rows included; excl = not cancelled);
     * HIGH_LEVEL from the view's page-index rows at each row's own (occurredAt, productId) -- a
     * session row already sits at the session key -- with excl = "visibleExclCancelled". Each row
     * counts under its own product (when it has one) and the sentinel 0. A productId of 0 counts
     * once, as the write path's key set does.
     */
    public List<String> counterDrift() {
        return dsl.fetch("""
                with src as (
                    select 1 as v, 0 as u, "userId", "occurredAt" as t, "productId" as p,
                           not "cancelled" as vis from "wqWebItem"
                  union all
                    select 2, 0, "userId", "occurredAt", "productId", not "cancelled" from "wqCcItem"
                  union all
                    select 1, 1, "userId", "occurredAt", "productId", "visibleExclCancelled"
                    from "wqWebHighLevel"
                  union all
                    select 2, 1, "userId", "occurredAt", "productId", "visibleExclCancelled"
                    from "wqCcHighLevel"
                ),
                keyed as (
                    select v, u, "userId", p as prod, t, vis from src where p is not null and p <> 0
                  union all
                    select v, u, "userId", 0, t, vis from src
                ),
                expected as (
                    select 'day' as tier, "userId", v, u, prod,
                           (t at time zone 'UTC')::date as period,
                           count(*) as "cntAll", count(*) filter (where vis) as "cntExcl"
                    from keyed group by 1, 2, 3, 4, 5, 6
                  union all
                    select 'month', "userId", v, u, prod,
                           date_trunc('month', t at time zone 'UTC')::date,
                           count(*), count(*) filter (where vis)
                    from keyed group by 1, 2, 3, 4, 5, 6
                ),
                stored as (
                    select 'day' as tier, "userId", "viewKind"::int as v, "pageUnit"::int as u,
                           "productId" as prod, "period", "cntAll", "cntExclCancelled" as "cntExcl"
                    from "wqCountDay"
                  union all
                    select 'month', "userId", "viewKind", "pageUnit", "productId", "period",
                           "cntAll", "cntExclCancelled"
                    from "wqCountMonth"
                )
                select case when e.tier is null then 'orphan counter row'
                            when s.tier is null then 'missing counter row'
                            else 'counter mismatch' end as problem,
                       coalesce(e.tier, s.tier) as tier,
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e.v, s.v) as "viewKind",
                       case coalesce(e.u, s.u) when 0 then 'ITEM' else 'HIGH_LEVEL' end as "pageUnit",
                       coalesce(e.prod, s.prod) as "productId",
                       coalesce(e.period, s.period) as period,
                       e."cntAll" as "expectedAll", s."cntAll" as "storedAll",
                       e."cntExcl" as "expectedExcl", s."cntExcl" as "storedExcl"
                from expected e
                full outer join stored s
                  on s.tier = e.tier and s."userId" = e."userId" and s.v = e.v and s.u = e.u
                 and s.prod = e.prod and s.period = e.period
                where e.tier is null or s.tier is null
                   or e."cntAll" <> s."cntAll" or e."cntExcl" <> s."cntExcl"
                order by 2, 3, 4, 5, 6, 7
                """)
                .map(ProjectionInvariants::describe);
    }

    /**
     * P-4: every session and play row equals a fold over its items, and "wqGroupLocator" points
     * at each group's row.
     *
     * Expected from the non-cancellation sessioned rows of "wqCcItem" (every source feeds /cc):
     * txCount and its ExclCancelled form; playCount = distinct non-null subBatchId,
     * playCountExclCancelled = plays with at least one non-cancelled item; totalPlays, totalWins
     * (abs(amount) by {@link MoneyDirection}, exactly as {@link RollupUpdater}) and net = wins -
     * plays, each with its ExclCancelled form; startedAt = MIN occurredAt, endedAt = MAX,
     * productId and currency = LEAST non-null. Session and play locators sit at the row's
     * startedAt. Orphan rows (no items) and missing rows are reported too.
     */
    public List<String> aggregationDrift() {
        String plays = money(MoneyDirection.PLAY);
        String wins = money(MoneyDirection.WIN);
        String sessioned = """
                select i."userId", i."batchId", i."subBatchId", i."occurredAt", i."productId",
                       i."currency"::text as currency, i."cancelled",
                       %s as plays, %s as wins
                from "wqCcItem" i
                where i."batchId" is not null and %s
                """.formatted(plays, wins, NOT_A_CANCELLATION);
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
                min("productId") as "productId", min(currency) as currency
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
                or e.currency is distinct from s.currency::text
                """;

        List<String> drift = new ArrayList<>();
        drift.addAll(dsl.fetch("""
                with items as (%1$s),
                expected as (
                    select "userId", "batchId", %2$s,
                           count(distinct "subBatchId")::int as "playCount",
                           count(distinct "subBatchId") filter (where not "cancelled")::int
                             as "playCountExclCancelled"
                    from items group by 1, 2
                )
                select case when e."batchId" is null then 'orphan session row'
                            when s."batchId" is null then 'missing session row'
                            else 'session mismatch' end as problem,
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e."batchId", s."batchId") as "batchId",
                       e::text as expected, s::text as stored
                from expected e
                full outer join "wqSession" s
                  on s."userId" = e."userId" and s."batchId" = e."batchId"
                where e."batchId" is null or s."batchId" is null
                   or %3$s
                   or e."playCount" is distinct from s."playCount"
                   or e."playCountExclCancelled" is distinct from s."playCountExclCancelled"
                order by 2, 3
                """.formatted(sessioned, folded, compared))
                .map(ProjectionInvariants::describe));
        drift.addAll(dsl.fetch("""
                with items as (%1$s),
                expected as (
                    select "userId", "batchId", "subBatchId", %2$s
                    from items where "subBatchId" is not null group by 1, 2, 3
                )
                select case when e."batchId" is null then 'orphan play row'
                            when s."batchId" is null then 'missing play row'
                            else 'play mismatch' end as problem,
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e."batchId", s."batchId") as "batchId",
                       coalesce(e."subBatchId", s."subBatchId") as "subBatchId",
                       e::text as expected, s::text as stored
                from expected e
                full outer join "wqPlay" s
                  on s."userId" = e."userId" and s."batchId" = e."batchId"
                 and s."subBatchId" = e."subBatchId"
                where e."batchId" is null or s."batchId" is null or %3$s
                order by 2, 3, 4
                """.formatted(sessioned, folded, compared))
                .map(ProjectionInvariants::describe));
        drift.addAll(dsl.fetch("""
                with items as (%1$s),
                expected as (
                    select "userId", "batchId", '' as "subBatchId", min("occurredAt") as "startedAt"
                    from items group by 1, 2
                  union all
                    select "userId", "batchId", "subBatchId", min("occurredAt")
                    from items where "subBatchId" is not null group by 1, 2, 3
                )
                select case when e."batchId" is null then 'orphan group locator'
                            when l."batchId" is null then 'missing group locator'
                            else 'group locator startedAt mismatch' end as problem,
                       coalesce(e."userId", l."userId") as "userId",
                       coalesce(e."batchId", l."batchId") as "batchId",
                       coalesce(e."subBatchId", l."subBatchId") as "subBatchId",
                       e."startedAt" as "expectedStartedAt", l."startedAt" as "storedStartedAt"
                from expected e
                full outer join "wqGroupLocator" l
                  on l."userId" = e."userId" and l."batchId" = e."batchId"
                 and l."subBatchId" = e."subBatchId"
                where e."batchId" is null or l."batchId" is null
                   or e."startedAt" <> l."startedAt"
                order by 2, 3, 4
                """.formatted(sessioned))
                .map(ProjectionInvariants::describe));
        return drift;
    }

    /**
     * P-8: each page-index table holds exactly the rows its view's items justify, at the right
     * key and with the right visibility.
     *
     * Per view V (its item table holds exactly the items whose source feeds V): one session row
     * for each session with at least one non-cancellation item in V, at the session key
     * (startedAt, productId) folded over ALL the session's non-cancellation items, visible iff at
     * least one of its non-cancellation items in V is not cancelled, carrying the session's
     * currency (LEAST over the same items as the key); and one row per non-cancellation
     * unsessioned item in V, at the item's own (occurredAt, productId) with its own currency,
     * visible iff the item is not cancelled. Rows are matched on (userId, kind, refId), so a row at the
     * wrong key reads as a key mismatch rather than an orphan plus a missing row.
     */
    public List<String> highLevelDrift() {
        List<String> drift = new ArrayList<>();
        for (String[] view : new String[][] {
                {"WEB", "wqWebItem", "wqWebHighLevel"}, {"CC", "wqCcItem", "wqCcHighLevel"}}) {
            drift.addAll(dsl.fetch("""
                    with session_key as (
                        select i."userId", i."batchId", min(i."occurredAt") as "startedAt",
                               min(i."productId") as "productId",
                               min(i."currency"::text) as currency
                        from "wqCcItem" i
                        where i."batchId" is not null and %3$s
                        group by 1, 2
                    ),
                    expected as (
                        select k."userId", k."startedAt" as "occurredAt", 0::smallint as kind,
                               k."batchId" as "refId", k."productId", k.currency,
                               bool_or(not i."cancelled") as visible
                        from "%1$s" i
                        join session_key k on k."userId" = i."userId" and k."batchId" = i."batchId"
                        where %3$s
                        group by 1, 2, 3, 4, 5, 6
                      union all
                        select i."userId", i."occurredAt", i."source", i."sourceId", i."productId",
                               i."currency"::text, not i."cancelled"
                        from "%1$s" i
                        where i."batchId" is null and %3$s
                    )
                    select case when e."refId" is null then 'orphan page row'
                                when h."refId" is null then 'missing page row'
                                when e."occurredAt" <> h."occurredAt"
                                  or e."productId" is distinct from h."productId"
                                  then 'page row key mismatch'
                                when e.currency is distinct from h."currency"::text
                                  then 'page row currency mismatch'
                                else 'page row visibility mismatch' end as problem,
                           '%4$s' as view,
                           coalesce(e."userId", h."userId") as "userId",
                           coalesce(e.kind, h."kind") as kind,
                           coalesce(e."refId", h."refId") as "refId",
                           e."occurredAt" as "expectedAt", h."occurredAt" as "storedAt",
                           e."productId" as "expectedProductId", h."productId" as "storedProductId",
                           e.currency as "expectedCurrency", h."currency" as "storedCurrency",
                           e.visible as "expectedVisible", h."visibleExclCancelled" as "storedVisible"
                    from expected e
                    full outer join "%2$s" h
                      on h."userId" = e."userId" and h."kind" = e.kind and h."refId" = e."refId"
                    where e."refId" is null or h."refId" is null
                       or e."occurredAt" <> h."occurredAt"
                       or e."productId" is distinct from h."productId"
                       or e.currency is distinct from h."currency"::text
                       or e.visible <> h."visibleExclCancelled"
                    order by 3, 4, 5
                    """.formatted(view[1], view[2], NOT_A_CANCELLATION, view[0]))
                    .map(ProjectionInvariants::describe));
        }
        return drift;
    }

    /**
     * abs(amount) for the item types {@link MoneyDirection} maps to {@code direction}, else 0.
     * The type list is read from the data and classified by MoneyDirection itself, so this can
     * never disagree with the write path's mapping.
     */
    private String money(MoneyDirection direction) {
        List<Short> types = dsl.fetch("select distinct \"type\" from \"wqCcItem\"")
                .getValues(0, Short.class).stream()
                .filter(type -> MoneyDirection.ofType(type) == direction)
                .toList();
        if (types.isEmpty()) {
            return "0::numeric";
        }
        String in = types.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return "case when i.\"type\" in (" + in + ") then abs(i.\"amount\") else 0 end";
    }

    private static String describe(Record r) {
        StringBuilder line = new StringBuilder(String.valueOf(r.get("problem")));
        for (int i = 1; i < r.size(); i++) {
            Object value = r.get(i);
            if (value instanceof java.time.OffsetDateTime at) {
                value = at.toInstant(); // UTC, whatever the session time zone
            }
            line.append(' ').append(r.field(i).getName()).append('=').append(value);
        }
        return line.toString();
    }
}
