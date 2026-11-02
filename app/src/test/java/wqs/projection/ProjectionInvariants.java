package wqs.projection;

import java.util.ArrayList;
import java.util.List;
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
 *   <li>A session's key is (MIN occurredAt, the FIRST item's productType) over its
 *       non-cancellation items, cancelled or not -- a cancel never moves a row. First is by
 *       ("occurredAt", "sourceId", "source") (plan D6); its productId is the same item's, so the
 *       pair always belongs to one item. A session is single-currency (mismatches are
 *       dead-lettered), so its currency is any item's -- MIN here.</li>
 *   <li>A zero-amount item is in the locator only ("zeroAmount"), in no table checked here; a
 *       zero-amount cancel's target is in the locator's "cancels" and still counts as a
 *       cancel.</li>
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
        all.addAll(cancellationDrift());
        return all;
    }

    /**
     * Cancellation itself (spec 6.3), per view item table: an item is "cancelled" exactly when a
     * cancel for it exists -- a cancel item row, or a zero-amount cancel's locator row -- same source, same user, "cancels" = its sourceId -- and then
     * "cancelledBy" is the least such cancel's sourceId (null otherwise). And "wqPendingCancel"
     * holds exactly the cancels whose target is absent (no locator row with that source,
     * sourceId and user).
     *
     * A drained cancel that failed and was dead-lettered (spec 6.3/6.4) legitimately reports here
     * as "cancel not applied": it is an incident awaiting replay, not a false positive.
     */
    public List<String> cancellationDrift() {
        List<String> drift = new ArrayList<>();
        String cancels = "c.\"cancels\" is not null and btrim(c.\"cancels\") <> ''";
        // Every cancel: projected cancel rows, and zero-amount cancels, which live in the locator
        // only (spec 4.1) yet still cancel their target.
        String allCancels = """
                select c."source", c."userId", c."sourceId", c."cancels" as target
                from "wqCcItem" c where %s
                union all
                select l."source", l."userId", l."sourceId", l."cancels"
                from "wqItemLocator" l where l."zeroAmount" and l."cancels" is not null
                """.formatted(cancels);
        for (String[] view : new String[][] {{"WEB", "wqWebItem"}, {"CC", "wqCcItem"}}) {
            drift.addAll(dsl.fetch("""
                    with cancel as (
                        select "source", "userId", target, min("sourceId") as "by"
                        from (%4$s) all_cancels
                        group by 1, 2, 3
                    )
                    select case when i."cancelled" and k.target is null then 'cancelled without a cancel'
                                when not i."cancelled" and k.target is not null then 'cancel not applied'
                                else 'cancelledBy mismatch' end as problem,
                           '%2$s' as view, i."userId", i."source", i."sourceId",
                           i."cancelled" as "storedCancelled",
                           k."by" as "expectedCancelledBy", i."cancelledBy" as "storedCancelledBy"
                    from "%1$s" i
                    left join cancel k
                      on k."source" = i."source" and k."userId" = i."userId" and k.target = i."sourceId"
                    where i."cancelled" <> (k.target is not null)
                       or i."cancelledBy" is distinct from k."by"
                    order by 3, 4, 5
                    """.formatted(view[1], view[0], cancels, allCancels))
                    .map(ProjectionInvariants::describe));
        }
        drift.addAll(dsl.fetch("""
                with expected as (
                    select c."source", c."sourceId", c.target as "cancels", c."userId"
                    from (%2$s) c
                    where not exists (select 1 from "wqItemLocator" l
                                      where l."source" = c."source" and l."sourceId" = c.target
                                        and l."userId" = c."userId")
                )
                select case when e."sourceId" is null then 'unexpected pending cancel'
                            else 'missing pending cancel' end as problem,
                       coalesce(e."userId", p."userId") as "userId",
                       coalesce(e."source", p."source") as "source",
                       coalesce(e."sourceId", p."cancelSourceId") as "cancelSourceId",
                       coalesce(e."cancels", p."cancelsSourceId") as "cancelsSourceId"
                from expected e
                full outer join "wqPendingCancel" p
                  on p."source" = e."source" and p."cancelSourceId" = e."sourceId"
                 and p."cancelsSourceId" = e."cancels" and p."userId" = e."userId"
                where e."sourceId" is null or p."cancelSourceId" is null
                order by 2, 3, 4
                """.formatted(cancels, allCancels))
                .map(ProjectionInvariants::describe));
        return drift;
    }

    /**
     * P-2: every counter row in both tiers equals a live COUNT, and no other counter row exists.
     *
     * Expected rows, per view, page unit, tier, product-type key, type key and UTC period (day,
     * or first of the month): ITEM from the view's item rows (cancellation rows included; excl =
     * not cancelled); HIGH_LEVEL from the view's page-index rows at each row's own (occurredAt,
     * productType) -- a session row already sits at the session key -- with excl =
     * "visibleExclCancelled". Each row counts under its own product type (when it has one) and the
     * ALL sentinel ''. A productType of '' counts once, as the write path's key set does.
     *
     * The type key (D4): an ITEM row counts under its own "type" and the ALL sentinel 0, crossed
     * with its product keys -- four keys, or two without a product type. HIGH_LEVEL rows never
     * carry a type: only the type sentinel 0.
     */
    public List<String> counterDrift() {
        return dsl.fetch("""
                with src as (
                    select 1 as v, 0 as u, "userId", "occurredAt" as t, "productType" as p,
                           not "cancelled" as vis, "type"::int as ty from "wqWebItem"
                  union all
                    select 2, 0, "userId", "occurredAt", "productType", not "cancelled", "type"::int
                    from "wqCcItem"
                  union all
                    select 1, 1, "userId", "occurredAt", "productType", "visibleExclCancelled", 0
                    from "wqWebHighLevel"
                  union all
                    select 2, 1, "userId", "occurredAt", "productType", "visibleExclCancelled", 0
                    from "wqCcHighLevel"
                ),
                keyed as (
                    select distinct v, u, "userId", pk.prod, tk.typ, t, vis, src_id
                    from (select row_number() over () as src_id, * from src) s,
                         lateral (values (s.p), ('')) as pk(prod),
                         lateral (values (s.ty), (0)) as tk(typ)
                    where pk.prod is not null
                ),
                expected as (
                    select 'day' as tier, "userId", v, u, prod, typ,
                           (t at time zone 'UTC')::date as period,
                           count(*) as "cntAll", count(*) filter (where vis) as "cntExcl"
                    from keyed group by 1, 2, 3, 4, 5, 6, 7
                  union all
                    select 'month', "userId", v, u, prod, typ,
                           date_trunc('month', t at time zone 'UTC')::date,
                           count(*), count(*) filter (where vis)
                    from keyed group by 1, 2, 3, 4, 5, 6, 7
                ),
                stored as (
                    select 'day' as tier, "userId", "viewKind"::int as v, "pageUnit"::int as u,
                           "productType" as prod, "typeId"::int as typ, "period", "cntAll",
                           "cntExclCancelled" as "cntExcl"
                    from "wqCountDay"
                  union all
                    select 'month', "userId", "viewKind", "pageUnit", "productType", "typeId",
                           "period", "cntAll", "cntExclCancelled"
                    from "wqCountMonth"
                )
                select case when e.tier is null then 'orphan counter row'
                            when s.tier is null then 'missing counter row'
                            else 'counter mismatch' end as problem,
                       coalesce(e.tier, s.tier) as tier,
                       coalesce(e."userId", s."userId") as "userId",
                       coalesce(e.v, s.v) as "viewKind",
                       case coalesce(e.u, s.u) when 0 then 'ITEM' else 'HIGH_LEVEL' end as "pageUnit",
                       coalesce(e.prod, s.prod) as "productType",
                       coalesce(e.typ, s.typ) as "typeId",
                       coalesce(e.period, s.period) as period,
                       e."cntAll" as "expectedAll", s."cntAll" as "storedAll",
                       e."cntExcl" as "expectedExcl", s."cntExcl" as "storedExcl"
                from expected e
                full outer join stored s
                  on s.tier = e.tier and s."userId" = e."userId" and s.v = e.v and s.u = e.u
                 and s.prod = e.prod and s.typ = e.typ and s.period = e.period
                where e.tier is null or s.tier is null
                   or e."cntAll" <> s."cntAll" or e."cntExcl" <> s."cntExcl"
                order by 2, 3, 4, 5, 6, 7, 8
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
     * (abs(amount) by the type's "wqTxType" direction, exactly as {@link RollupUpdater}) and net
     * = wins - plays, each with its ExclCancelled form; startedAt = MIN occurredAt, endedAt = MAX,
     * productId and productType = the first item's pair by (occurredAt, sourceId, source),
     * currency = MIN (the session is single-currency). Session and play locators sit at the row's
     * startedAt. Orphan rows (no items) and missing rows are reported too.
     */
    public List<String> aggregationDrift() {
        String plays = money(MoneyDirection.PLAY);
        String wins = money(MoneyDirection.WIN);
        String sessioned = """
                select i."userId", i."batchId", i."subBatchId", i."occurredAt", i."productId",
                       i."productType", i."currency"::text as currency, i."cancelled",
                       i."sourceId", i."source",
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
     * (startedAt, productType) over ALL the session's non-cancellation items -- the product being
     * the first item's by (occurredAt, sourceId, source), productId from the same item and carried
     * on the row -- visible iff at least one of its non-cancellation items in V is not cancelled,
     * carrying the session's currency; and one row per non-cancellation
     * unsessioned item in V, at the item's own (occurredAt, productType) with its own productId
     * and currency,
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
                               (array_agg(i."productId"
                                    order by i."occurredAt", i."sourceId", i."source"))[1] as "productId",
                               (array_agg(i."productType"
                                    order by i."occurredAt", i."sourceId", i."source"))[1] as "productType",
                               min(i."currency"::text) as currency
                        from "wqCcItem" i
                        where i."batchId" is not null and %3$s
                        group by 1, 2
                    ),
                    expected as (
                        select k."userId", k."startedAt" as "occurredAt", 0::smallint as kind,
                               k."batchId" as "refId", k."productId", k."productType", k.currency,
                               bool_or(not i."cancelled") as visible
                        from "%1$s" i
                        join session_key k on k."userId" = i."userId" and k."batchId" = i."batchId"
                        where %3$s
                        group by 1, 2, 3, 4, 5, 6, 7
                      union all
                        select i."userId", i."occurredAt", i."source", i."sourceId", i."productId",
                               i."productType", i."currency"::text, not i."cancelled"
                        from "%1$s" i
                        where i."batchId" is null and %3$s
                    )
                    select case when e."refId" is null then 'orphan page row'
                                when h."refId" is null then 'missing page row'
                                when e."occurredAt" <> h."occurredAt"
                                  or e."productId" is distinct from h."productId"
                                  or e."productType" is distinct from h."productType"
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
                           e."productType" as "expectedProductType",
                           h."productType" as "storedProductType",
                           e.currency as "expectedCurrency", h."currency" as "storedCurrency",
                           e.visible as "expectedVisible", h."visibleExclCancelled" as "storedVisible"
                    from expected e
                    full outer join "%2$s" h
                      on h."userId" = e."userId" and h."kind" = e.kind and h."refId" = e."refId"
                    where e."refId" is null or h."refId" is null
                       or e."occurredAt" <> h."occurredAt"
                       or e."productId" is distinct from h."productId"
                       or e."productType" is distinct from h."productType"
                       or e.currency is distinct from h."currency"::text
                       or e.visible <> h."visibleExclCancelled"
                    order by 3, 4, 5
                    """.formatted(view[1], view[2], NOT_A_CANCELLATION, view[0]))
                    .map(ProjectionInvariants::describe));
        }
        return drift;
    }

    /**
     * P-7 (spec 7.4), the stored half: every non-cancellation item whose product type is eInstant
     * carries a non-null "batchId", in both item tables, and so neither view's page index holds an
     * unsessioned eInstant row. {@code eInstantProductTypes} is {@code wqs.einstant.product-types}
     * (plan D3); the classification is configuration, which is why this cannot be a check
     * constraint. Cancellations are exempt, as at ingest (IngestRules): they never get a page row.
     */
    public List<String> eInstantDrift(java.util.Collection<String> eInstantProductTypes) {
        List<String> drift = new ArrayList<>();
        if (eInstantProductTypes.isEmpty()) {
            return drift;
        }
        String[] types = eInstantProductTypes.toArray(String[]::new);
        for (String[] view : new String[][] {
                {"WEB", "wqWebItem", "wqWebHighLevel"}, {"CC", "wqCcItem", "wqCcHighLevel"}}) {
            drift.addAll(dsl.fetch("""
                    select 'unsessioned eInstant item' as problem, '%3$s' as view,
                           i."userId", i."source", i."sourceId", i."productType"
                    from "%1$s" i
                    where i."batchId" is null and i."productType" = any(?::text[]) and %4$s
                    order by 3, 4, 5
                    """.formatted(view[1], view[2], view[0], NOT_A_CANCELLATION), (Object) types)
                    .map(ProjectionInvariants::describe));
            drift.addAll(dsl.fetch("""
                    select 'unsessioned eInstant page row' as problem, '%2$s' as view,
                           h."userId", h."kind", h."refId", h."productType"
                    from "%1$s" h
                    where h."kind" <> 0 and h."productType" = any(?::text[])
                    order by 3, 4, 5
                    """.formatted(view[2], view[0]), (Object) types)
                    .map(ProjectionInvariants::describe));
        }
        return drift;
    }

    /**
     * abs(amount) for the item types whose "wqTxType" direction is {@code direction}, else 0.
     * The direction is read from the same table the write path's registry reads, so this can
     * never disagree with the write path's mapping.
     */
    private static String money(MoneyDirection direction) {
        return "case when i.\"type\" in (select t.\"typeId\" from \"wqTxType\" t where t.\"direction\" = "
                + direction.code() + ") then abs(i.\"amount\") else 0 end";
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
