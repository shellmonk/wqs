package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.domain.SourceKind.PAYMENT;
import static wqs.domain.SourceKind.TRANSACTION;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.domain.ProjectionItem;
import wqs.gen.TxTypes;
import wqs.read.cc.CcLevel0Query;
import wqs.read.web.WebLevel0Query;

class Level0QueryTest extends Level0TestSupport {

    @Autowired WebLevel0Query web;
    @Autowired CcLevel0Query cc;

    @BeforeEach
    void clean() {
        reset();
    }

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    @Test
    void count_respects_half_open_bounds_and_excl() {
        project(
                item(TRANSACTION, "a", "2034-02-01T00:00:00Z", "P11", null, null),
                item(TRANSACTION, "b", "2034-01-31T23:59:59Z", "P13", null, null),
                item(TRANSACTION, "c", "2034-01-31T12:00:00Z", "P11", null, null),
                item(TRANSACTION, "x", "2034-01-31T13:00:00Z", "P11", "c", null));
        Level0Filter all = new Level0Filter(USER, false);
        Level0Filter excl = new Level0Filter(USER, true);

        assertThat(web.count(readerDsl, all, t("2034-02-01T00:00:00Z"), t("2034-02-02T00:00:00Z"))).isEqualTo(1);
        // the boundary item at 02-01T00:00 is excluded by the exclusive upper bound
        assertThat(web.count(readerDsl, all, t("2034-01-31T00:00:00Z"), t("2034-02-01T00:00:00Z"))).isEqualTo(3);
        assertThat(web.count(readerDsl, all, t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isEqualTo(4);
        // c is cancelled; the cancellation row x is itself an ITEM row and counts
        assertThat(web.count(readerDsl, excl, t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isEqualTo(3);
        assertThat(web.count(readerDsl, new Level0Filter(USER + 1, false),
                t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isZero();
    }

    @Test
    void slice_orders_ties_by_itemId_desc() {
        // Ascending itemIds, and the plan is forced to seq-scan + sort so index order cannot
        // mask a missing tie-break.
        for (int i = 0; i < 12; i++) {
            project(item(TRANSACTION, "t" + i, "2034-02-10T10:00:00Z", "P11", null, null));
        }
        Level0Filter f = new Level0Filter(USER, false);
        Instant lo = t("2034-02-01T00:00:00Z");
        Instant hi = t("2034-03-01T00:00:00Z");
        List<ItemKey> all = readerDsl.transactionResult(cfg -> {
            var tx = org.jooq.impl.DSL.using(cfg);
            tx.execute("set local enable_indexscan = off");
            tx.execute("set local enable_indexonlyscan = off");
            tx.execute("set local enable_bitmapscan = off");
            return web.keys(tx, f, lo, hi, 0, 20);
        });
        assertThat(all).hasSize(12);
        assertThat(all).extracting(ItemKey::itemId).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        List<ItemKey> page = readerDsl.transactionResult(cfg -> {
            var tx = org.jooq.impl.DSL.using(cfg);
            tx.execute("set local enable_indexscan = off");
            tx.execute("set local enable_indexonlyscan = off");
            tx.execute("set local enable_bitmapscan = off");
            return web.keys(tx, f, lo, hi, 2, 2);
        });
        assertThat(page).isEqualTo(all.subList(2, 4));
    }

    @Test
    void fetch_handles_a_very_large_key_list() {
        partitionManager.ensurePartitionsAhead(java.time.YearMonth.of(2034, 5), 0);
        Instant base = t("2034-05-01T00:00:00Z");
        try {
            // One set-based statement instead of a 40k-row batchInsert.
            dsl().execute("""
                    insert into "wqWebItem" ("userId", "itemId", "source", "sourceId", "occurredAt",
                                             "type", "amount", "currency")
                    select ?, 9000000000 + i, 1, 'bulk' || i,
                           timestamptz '2034-05-01T00:00:00Z' + i * interval '1 second', 1, 1, 'EUR'
                    from generate_series(0, 39999) as i
                    """, USER);
            Level0Filter f = new Level0Filter(USER, false);
            List<ItemKey> keys = web.keys(readerDsl, f, base, base.plusSeconds(50_000), 0, 50_000);
            assertThat(keys).hasSize(40_000);
            List<ItemEntry> entries = web.fetch(readerDsl, USER, keys);
            assertThat(entries).hasSize(40_000);
            assertThat(entries).extracting(ItemEntry::occurredAt)
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(entries.get(0).sourceId()).isEqualTo("bulk39999");
        } finally {
            clearProjection();
        }
    }

    /**
     * F1 (final review): the key array is one bind joined through unnest, which Postgres
     * evaluates once. "itemId" = any(?) re-evaluated the text-bound array per row (40k keys:
     * 44 s). Timing-free: asserts the executed statement's text and its plan.
     */
    @Test
    void fetch_joins_the_key_array_through_unnest_not_a_per_row_any() {
        project(
                item(TRANSACTION, "a", "2034-02-01T01:00:00Z", "P11", null, null),
                item(PAYMENT, "b", "2034-02-02T01:00:00Z", "P11", null, null));
        Level0Filter f = new Level0Filter(USER, false);
        Instant lo = t("2034-02-01T00:00:00Z");
        Instant hi = t("2034-03-01T00:00:00Z");
        for (Level0Query q : List.of(web, cc)) {
            List<org.jooq.Query> executed = new java.util.ArrayList<>();
            org.jooq.DSLContext recording = org.jooq.impl.DSL.using(readerDsl.configuration().derive(
                    org.jooq.ExecuteListener.onExecuteStart(ctx -> executed.add(ctx.query()))));
            List<ItemKey> keys = q.keys(readerDsl, f, lo, hi, 0, 10);
            assertThat(q.fetch(recording, USER, keys)).hasSameSizeAs(keys).isNotEmpty();

            assertThat(executed).as(q.view() + " fetch statements").hasSize(1);
            String sql = executed.get(0).getSQL();
            assertThat(sql).as(q.view() + " sql").contains("unnest(cast(? as bigint[]))")
                    .doesNotContainIgnoringCase("any (").doesNotContain("::cstring");
            String plan = String.join("\n", readerDsl.fetch(org.jooq.impl.DSL.resultQuery("explain {0}", executed.get(0)))
                    .getValues(0, String.class));
            assertThat(plan).as(q.view() + " plan").contains("Function Scan on unnest").doesNotContain("ANY (");
        }
    }

    @Test
    void fetch_returns_payload_in_key_order_with_raw_detail() throws Exception {
        project(
                item(TRANSACTION, "jan", "2034-01-31T23:00:00Z", "P11", null, "{\"gameCode\": \"x\"}"),
                item(TRANSACTION, "feb1", "2034-02-01T01:00:00Z", "P11", null, null),
                item(TRANSACTION, "feb2", "2034-02-02T01:00:00Z", "P11", null, null));
        Level0Filter f = new Level0Filter(USER, false);
        List<ItemKey> keys = web.keys(readerDsl, f, t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"), 0, 10);
        assertThat(keys).hasSize(3);

        List<ItemEntry> entries = web.fetch(readerDsl, USER, keys);
        assertThat(entries).extracting(ItemEntry::sourceId).containsExactly("feb2", "feb1", "jan");
        ItemEntry jan = entries.get(2);
        assertThat(jan.kind()).isEqualTo("TRANSACTION");
        assertThat(jan.occurredAt()).isEqualTo(t("2034-01-31T23:00:00Z"));
        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(jan.detail())).isEqualTo(om.readTree("{\"gameCode\": \"x\"}"));
        assertThat(entries.get(0).detail()).isNull();

        List<ItemKey> reversed = List.of(keys.get(2), keys.get(0));
        assertThat(web.fetch(readerDsl, USER, reversed)).extracting(ItemEntry::sourceId)
                .containsExactly("jan", "feb2");
        assertThat(web.fetch(readerDsl, USER, List.of())).isEmpty();
    }

    @Test
    void web_query_never_sees_cc_only_sources() {
        project(item(PAYMENT, "pay", "2034-02-10T10:00:00Z", "P11", null, null));
        Level0Filter f = new Level0Filter(USER, false);
        Instant lo = t("2034-02-01T00:00:00Z");
        Instant hi = t("2034-03-01T00:00:00Z");
        assertThat(web.count(readerDsl, f, lo, hi)).isZero();
        assertThat(cc.count(readerDsl, f, lo, hi)).isEqualTo(1);
        assertThat(cc.fetch(readerDsl, USER, cc.keys(readerDsl, f, lo, hi, 0, 10)))
                .extracting(ItemEntry::kind).containsExactly("PAYMENT");
    }

    private static ProjectionItem typed(String id, String at, String productType, short type) {
        return new ProjectionItem(USER, TRANSACTION, id, Instant.parse(at), productType == null ? null : "id-" + id,
                productType, null, null, type, new java.math.BigDecimal("1.0000"), "EUR", null, null, null);
    }

    @Test
    void list_filters_narrow_by_product_type_and_transaction_type_and_name_the_type() {
        project(
                typed("a", "2034-02-01T01:00:00Z", "keno", TxTypes.PLAY),
                typed("b", "2034-02-01T02:00:00Z", "keno", TxTypes.WIN),
                typed("c", "2034-02-01T03:00:00Z", "lottery", TxTypes.PLAY),
                typed("d", "2034-02-01T04:00:00Z", null, TxTypes.PLAY));
        Instant lo = t("2034-02-01T00:00:00Z");
        Instant hi = t("2034-03-01T00:00:00Z");
        for (Level0Query q : List.of(web, cc)) {
            assertThat(q.count(readerDsl, new Level0Filter(USER, java.util.Set.of("keno"), java.util.Set.of(), false), lo, hi))
                    .as(q.view() + " productTypes").isEqualTo(2);
            assertThat(q.count(readerDsl, new Level0Filter(USER, java.util.Set.of(), java.util.Set.of(TxTypes.PLAY), false), lo, hi))
                    .as(q.view() + " typeIds").isEqualTo(3);
            assertThat(q.count(readerDsl, new Level0Filter(USER, java.util.Set.of("keno", "lottery"),
                    java.util.Set.of(TxTypes.PLAY), false), lo, hi)).as(q.view() + " both").isEqualTo(2);
            assertThat(q.count(readerDsl, new Level0Filter(USER, java.util.Set.of("nope"), java.util.Set.of(), false), lo, hi))
                    .as(q.view() + " unknown product type matches nothing").isZero();
            Level0Filter f = new Level0Filter(USER, java.util.Set.of("keno"), java.util.Set.of(TxTypes.WIN), false);
            List<ItemEntry> got = q.fetch(readerDsl, USER, q.keys(readerDsl, f, lo, hi, 0, 10));
            assertThat(got).extracting(ItemEntry::sourceId).containsExactly("b");
            assertThat(got.get(0).productType()).isEqualTo("keno");
            assertThat(got.get(0).productId()).isEqualTo("id-b");
            assertThat(got.get(0).transactionType()).isEqualTo("PRIZE");
        }
    }

    /**
     * Plan D4/Task 5: after ANALYZE on realistic data, the default plan (no planner setting
     * touched) for a filtered key query is an index-only scan of the V10 covering index -- the
     * filter columns ride in INCLUDE -- not a seq scan.
     */
    @Test
    void list_filters_use_covering_index() {
        partitionManager.ensurePartitionsAhead(java.time.YearMonth.of(2034, 5), 0);
        try {
            // 60 users x 2000 rows over May 2034; USER is one of them. Set-based, like the bulk test.
            dsl().execute("""
                    insert into "wqWebItem" ("userId", "itemId", "source", "sourceId", "occurredAt",
                                             "type", "amount", "currency", "productType")
                    select ?::bigint + (i % 60), 9100000000 + i, 1, 'cov' || i,
                           timestamptz '2034-05-01T00:00:00Z' + (i / 60) * interval '20 minutes' + (i % 60) * interval '1 second',
                           1 + (i % 3), 1, 'EUR', (array['keno','lottery','bingo'])[1 + (i % 3)]
                    from generate_series(0, 119999) as i
                    """, USER);
            // vacuum sets the visibility map, which an index-only scan needs; no planner setting is touched.
            dsl().execute("vacuum (analyze) \"wqWebItem\"");
            Level0Filter f = new Level0Filter(USER, java.util.Set.of("keno", "bingo"),
                    java.util.Set.of(TxTypes.PLAY), false);
            List<org.jooq.Query> executed = new java.util.ArrayList<>();
            org.jooq.DSLContext recording = org.jooq.impl.DSL.using(readerDsl.configuration().derive(
                    org.jooq.ExecuteListener.onExecuteStart(ctx -> executed.add(ctx.query()))));
            web.keys(recording, f, t("2034-05-10T00:00:00Z"), t("2034-05-17T00:00:00Z"), 0, 50);
            assertThat(executed).hasSize(1);
            String plan = String.join("\n", readerDsl.fetch(org.jooq.impl.DSL.resultQuery("explain {0}", executed.get(0)))
                    .getValues(0, String.class));
            assertThat(plan).containsPattern("Index Only Scan using \"wqWebItem_\\d+_\\d+_userId_occurredAt_itemId_cancelled_produc").doesNotContain("Seq Scan");
        } finally {
            clearProjection();
        }
    }
}
