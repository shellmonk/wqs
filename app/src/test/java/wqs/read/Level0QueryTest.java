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
    void count_respects_half_open_bounds_product_and_excl() {
        project(
                item(TRANSACTION, "a", "2034-02-01T00:00:00Z", 11, null, null),
                item(TRANSACTION, "b", "2034-01-31T23:59:59Z", 13, null, null),
                item(TRANSACTION, "c", "2034-01-31T12:00:00Z", 11, null, null),
                item(TRANSACTION, "x", "2034-01-31T13:00:00Z", 11, "c", null));
        Level0Filter all = new Level0Filter(USER, null, false);
        Level0Filter excl = new Level0Filter(USER, null, true);
        Level0Filter p11 = new Level0Filter(USER, 11, false);

        assertThat(web.count(readerDsl, all, t("2034-02-01T00:00:00Z"), t("2034-02-02T00:00:00Z"))).isEqualTo(1);
        // the boundary item at 02-01T00:00 is excluded by the exclusive upper bound
        assertThat(web.count(readerDsl, all, t("2034-01-31T00:00:00Z"), t("2034-02-01T00:00:00Z"))).isEqualTo(3);
        assertThat(web.count(readerDsl, all, t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isEqualTo(4);
        // c is cancelled; the cancellation row x is itself an ITEM row and counts
        assertThat(web.count(readerDsl, excl, t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isEqualTo(3);
        assertThat(web.count(readerDsl, p11, t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isEqualTo(3);
        assertThat(web.count(readerDsl, new Level0Filter(USER, 13, true),
                t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isEqualTo(1);
        assertThat(web.count(readerDsl, new Level0Filter(USER + 1, null, false),
                t("2034-01-01T00:00:00Z"), t("2034-03-01T00:00:00Z"))).isZero();
    }

    @Test
    void slice_orders_ties_by_itemId_desc() {
        // Ascending itemIds, and the plan is forced to seq-scan + sort so index order cannot
        // mask a missing tie-break.
        for (int i = 0; i < 12; i++) {
            project(item(TRANSACTION, "t" + i, "2034-02-10T10:00:00Z", 11, null, null));
        }
        Level0Filter f = new Level0Filter(USER, null, false);
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
        var t = wqs.db.generated.Tables.WQ_WEB_ITEM;
        Instant base = t("2034-05-01T00:00:00Z");
        var records = new java.util.ArrayList<wqs.db.generated.tables.records.WqWebItemRecord>();
        for (int i = 0; i < 40_000; i++) {
            var r = dsl().newRecord(t);
            r.setUserId(USER);
            r.setItemId(9_000_000_000L + i);            r.setSource((short) 1);
            r.setSourceId("bulk" + i);
            r.setOccurredAt(base.plusSeconds(i).atOffset(java.time.ZoneOffset.UTC));
            r.setType((short) 1);
            r.setAmount(java.math.BigDecimal.ONE);
            r.setCurrency("EUR");
            records.add(r);
        }
        try {
            dsl().batchInsert(records).execute();
            Level0Filter f = new Level0Filter(USER, null, false);
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

    @Test
    void fetch_returns_payload_in_key_order_with_raw_detail() throws Exception {
        project(
                item(TRANSACTION, "jan", "2034-01-31T23:00:00Z", 11, null, "{\"gameCode\": \"x\"}"),
                item(TRANSACTION, "feb1", "2034-02-01T01:00:00Z", 11, null, null),
                item(TRANSACTION, "feb2", "2034-02-02T01:00:00Z", 11, null, null));
        Level0Filter f = new Level0Filter(USER, null, false);
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
        project(item(PAYMENT, "pay", "2034-02-10T10:00:00Z", 11, null, null));
        Level0Filter f = new Level0Filter(USER, null, false);
        Instant lo = t("2034-02-01T00:00:00Z");
        Instant hi = t("2034-03-01T00:00:00Z");
        assertThat(web.count(readerDsl, f, lo, hi)).isZero();
        assertThat(cc.count(readerDsl, f, lo, hi)).isEqualTo(1);
        assertThat(cc.fetch(readerDsl, USER, cc.keys(readerDsl, f, lo, hi, 0, 10)))
                .extracting(ItemEntry::kind).containsExactly("PAYMENT");
    }
}
