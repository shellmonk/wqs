package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Ground-truth checks over the stored projection of one user, independent of the path that
 * produced it.
 *
 * <ul>
 *   <li>P-2: for each view, page unit, tier, product (and the all-products sentinel) and period,
 *       the stored (cntAll, cntExclCancelled) equals a live COUNT over the item table or page
 *       index. Zero counter rows are ignored.</li>
 *   <li>Visibility (spec 6.3, per view): a session row in view V is visible exactly when the
 *       session has a surviving (non-cancelled, non-cancellation) item in V's item table; an
 *       unsessioned row is visible exactly when its item is not cancelled.</li>
 * </ul>
 */
final class ProjectionOracle {

    private ProjectionOracle() {}

    static void assertConsistent(DSLContext dsl, long userId) {
        assertCountersMatch(dsl, userId);
        assertVisibilityMatchesItems(dsl, userId);
    }

    static void assertCountersMatch(DSLContext dsl, long userId) {
        for (int view : List.of(1, 2)) {
            String items = view == 1 ? "wqWebItem" : "wqCcItem";
            String pages = view == 1 ? "wqWebHighLevel" : "wqCcHighLevel";
            for (String tier : List.of("day", "month")) {
                assertThat(stored(dsl, userId, view, 0, tier))
                        .as("ITEM %s counters, view %d", tier, view)
                        .isEqualTo(live(dsl, userId, items, "not \"cancelled\"", tier));
                assertThat(stored(dsl, userId, view, 1, tier))
                        .as("HIGH_LEVEL %s counters, view %d", tier, view)
                        .isEqualTo(live(dsl, userId, pages, "\"visibleExclCancelled\"", tier));
            }
        }
    }

    static void assertVisibilityMatchesItems(DSLContext dsl, long userId) {
        for (int view : List.of(1, 2)) {
            String items = view == 1 ? "wqWebItem" : "wqCcItem";
            String pages = view == 1 ? "wqWebHighLevel" : "wqCcHighLevel";
            List<String> mismatches = dsl.fetch("""
                    select h."kind" || '/' || h."refId" || ' stored=' || h."visibleExclCancelled"
                    from "%2$s" h
                    where h."userId" = ? and h."visibleExclCancelled" <> (
                        case when h."kind" = 0 then exists (
                                 select 1 from "%1$s" i
                                 where i."userId" = h."userId" and i."batchId" = h."refId"
                                   and not i."cancelled"
                                   and (i."cancels" is null or btrim(i."cancels") = ''))
                             else exists (
                                 select 1 from "%1$s" i
                                 where i."userId" = h."userId" and i."source" = h."kind"
                                   and i."sourceId" = h."refId" and not i."cancelled")
                        end)
                    """.formatted(items, pages), userId)
                    .getValues(0, String.class);
            assertThat(mismatches).as("page-index visibility vs surviving items, view %d", view)
                    .isEmpty();
        }
    }

    private static Map<String, String> stored(
            DSLContext dsl, long userId, int view, int pageUnit, String tier) {
        String table = tier.equals("day") ? "wqCountDay" : "wqCountMonth";
        return toKeyed(dsl.fetch("""
                select "productId" || '|' || "period"::text as k,
                       "cntAll" || '/' || "cntExclCancelled" as v
                from "%s"
                where "userId" = ? and "viewKind" = ? and "pageUnit" = ? and "cntAll" > 0
                """.formatted(table), userId, (short) view, (short) pageUnit));
    }

    private static Map<String, String> live(
            DSLContext dsl, long userId, String table, String visible, String tier) {
        String period = tier.equals("day")
                ? "(\"occurredAt\" at time zone 'UTC')::date"
                : "date_trunc('month', \"occurredAt\" at time zone 'UTC')::date";
        return toKeyed(dsl.fetch("""
                with r as (
                    select "productId" as p, %1$s as d, %2$s as vis from "%3$s" where "userId" = ?
                    union all
                    select 0, %1$s, %2$s from "%3$s" where "userId" = ?
                )
                select p || '|' || d::text as k,
                       count(*) || '/' || count(*) filter (where vis) as v
                from r where p is not null group by p, d
                """.formatted(period, visible, table), userId, userId));
    }

    private static Map<String, String> toKeyed(org.jooq.Result<Record> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.get("k", String.class), r -> r.get("v", String.class)));
    }
}
