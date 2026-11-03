package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;
import wqs.read.cc.CcHighLevelAssembly;
import wqs.read.cc.CcProjectionService;
import wqs.read.web.WebHighLevelAssembly;
import wqs.read.web.WebProjectionService;

/**
 * Level-1/2 assembly (spec 4.1, 7.3, 7.4; plan D6, D11) on one fixed dataset in Feb 2034, user 1:
 *
 * <pre>
 *   12:00  session s2 (keno):      s2-a TT_BUY 2 (play q1), s2-b PRIZE 3 (play q1)
 *   11:00  unsessioned u1 TT_TRANSFER_OUT 7
 *   10:00  session s1 (instants):  s1-a TT_BUY 10 (p1) 10:00, s1-b PRIZE 4 (p1) 10:01,
 *                                  s1-c TT_BUY 5 (p2) 10:02 -- cancelled by x-s1-c (10:02:30, in s1,
 *                                  no play), s1-d TT_TRANSFER_OUT 1 (no play) 10:03
 *   09:00  unsessioned u0 TT_BUY 6
 * </pre>
 *
 * s1 totals: all plays 15 / wins 4 / net -11 / 2 plays; excl plays 10 / wins 4 / net -6 / 1 play.
 */
@SpringBootTest
class HighLevelAssemblyTest extends ReadPropertyBase {

    private static final long USER = 1L;
    private static final Instant FROM = Instant.parse("2034-02-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2034-03-01T00:00:00Z");

    @Autowired WebProjectionService web;
    @Autowired CcProjectionService cc;
    @Autowired HighLevelAssembler assembler;
    @Autowired WebHighLevelAssembly webAssembly;
    @Autowired CcHighLevelAssembly ccAssembly;
    @Autowired wqs.db.TxTypeRegistry txTypes;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("readerDataSource")
    javax.sql.DataSource readerDataSource;

    @BeforeEach
    void seed() {
        reset();
        applyBatches(List.of(
                item("u0", "2034-02-10T09:00:00Z", null, null, TxTypes.PLAY, "6", "keno"),
                item("s1-a", "2034-02-10T10:00:00Z", "s1", "p1", TxTypes.PLAY, "10", "instants"),
                item("s1-b", "2034-02-10T10:01:00Z", "s1", "p1", TxTypes.WIN, "4", "keno"),
                item("s1-c", "2034-02-10T10:02:00Z", "s1", "p2", TxTypes.PLAY, "5", "keno"),
                cancel("x-s1-c", "2034-02-10T10:02:30Z", "s1", "s1-c"),
                item("s1-d", "2034-02-10T10:03:00Z", "s1", null, TxTypes.NEUTRAL, "1", "keno"),
                item("u1", "2034-02-10T11:00:00Z", null, null, TxTypes.NEUTRAL, "7", null),
                item("s2-a", "2034-02-10T12:00:00Z", "s2", "q1", TxTypes.PLAY, "2", "keno"),
                item("s2-b", "2034-02-10T12:30:00Z", "s2", "q1", TxTypes.WIN, "3", "keno")));
    }

    @Test
    void l1_nests_session_txs_newest_first() {
        for (ViewKind v : ViewKind.values()) {
            ProjectionResponse<HighLevelEntry> r = read(v, AggregationLevel.L1, false, Set.of(), 1, 10);
            assertThat(r.totalRowCount()).isEqualTo(4);
            assertThat(r.pagesAvailable()).isEqualTo(1);
            assertThat(r.pageSize()).isEqualTo(10);
            assertThat(ids(r.data())).containsExactly("s2", "u1", "s1", "u0");
            SessionView s1 = (SessionView) r.data().get(2);
            assertThat(s1.kind()).isEqualTo("SESSION");
            assertThat(s1.created()).isEqualTo("2034-02-10T10:00:00Z");
            assertThat(s1.endedAt()).isEqualTo("2034-02-10T10:03:00Z");
            // D6: the first item's product pair, not LEAST
            assertThat(s1.productId()).isEqualTo("P-instants");
            assertThat(s1.productType()).isEqualTo("instants");
            assertThat(s1.currency()).isEqualTo("EUR");
            assertThat(s1.totalPlay()).isEqualByComparingTo("15");
            assertThat(s1.totalWin()).isEqualByComparingTo("4");
            assertThat(s1.amount()).isEqualByComparingTo("-11");
            assertThat(s1.playCount()).isEqualTo(2);
            assertThat(s1.subitems()).isNull();
            assertThat(s1.txs()).extracting(TxView::txId).containsExactly("s1-d", "x-s1-c", "s1-c", "s1-b", "s1-a");
            assertThat(s1.txs()).extracting(TxView::cancelled).containsExactly(false, false, true, false, false);
        }
    }

    @Test
    void l2_nests_plays_then_txs() {
        for (ViewKind v : ViewKind.values()) {
            ProjectionResponse<HighLevelEntry> r = read(v, AggregationLevel.L2, false, Set.of(), 1, 10);
            assertThat(ids(r.data())).containsExactly("s2", "u1", "s1", "u0");
            SessionView s1 = (SessionView) r.data().get(2);
            assertThat(s1.totalPlay()).isEqualByComparingTo("15");
            assertThat(s1.subitems()).extracting(PlayView::subBatchId).containsExactly("p2", "p1");
            PlayView p1 = s1.subitems().get(1);
            assertThat(p1.playTimestamp()).isEqualTo("2034-02-10T10:00:00Z");
            assertThat(p1.playAmount()).isEqualByComparingTo("10");
            assertThat(p1.winAmount()).isEqualByComparingTo("4");
            assertThat(p1.amount()).isEqualByComparingTo("-6");
            assertThat(p1.txs()).extracting(TxView::txId).containsExactly("s1-b", "s1-a");
            assertThat(s1.subitems().get(0).txs()).extracting(TxView::txId).containsExactly("s1-c");
            // play-less transactions stay on the session at level 2
            assertThat(s1.txs()).extracting(TxView::txId).containsExactly("s1-d", "x-s1-c");
            SessionView s2 = (SessionView) r.data().get(0);
            assertThat(s2.txs()).isNull();
            assertThat(s2.subitems()).hasSize(1);
            assertThat(s2.subitems().get(0).txs()).extracting(TxView::txId).containsExactly("s2-b", "s2-a");
        }
    }

    @Test
    void unsessioned_items_interleave_by_time() {
        for (ViewKind v : ViewKind.values()) {
            for (AggregationLevel level : List.of(AggregationLevel.L1, AggregationLevel.L2)) {
                List<HighLevelEntry> all = new ArrayList<>();
                for (int page = 1; page <= 5; page++) {
                    ProjectionResponse<HighLevelEntry> r = read(v, level, false, Set.of(), page, 1);
                    assertThat(r.totalRowCount()).isEqualTo(4);
                    assertThat(r.pagesAvailable()).isEqualTo(4);
                    assertThat(r.pageNumber()).isEqualTo(page);
                    all.addAll(r.data());
                }
                assertThat(ids(all)).as(v + " " + level).containsExactly("s2", "u1", "s1", "u0");
                assertThat(all.get(1)).isInstanceOf(TxView.class);
                assertThat(((TxView) all.get(1)).kind()).isEqualTo("TRANSACTION");
                assertThat(((TxView) all.get(1)).amount()).isEqualByComparingTo("7");
            }
        }
    }

    @Test
    void transaction_types_filter_nested_txs_only() {
        for (ViewKind v : ViewKind.values()) {
            ProjectionResponse<HighLevelEntry> l1 = read(v, AggregationLevel.L1, false, Set.of("PRIZE"), 1, 10);
            assertThat(l1.totalRowCount()).isEqualTo(4);
            assertThat(l1.pagesAvailable()).isEqualTo(1);
            // every session and unsessioned item still appears; u0/u1 are not PRIZE
            assertThat(ids(l1.data())).containsExactly("s2", "u1", "s1", "u0");
            SessionView s1 = (SessionView) l1.data().get(2);
            assertThat(s1.txs()).extracting(TxView::txId).containsExactly("s1-b");
            // totals are the stored rollup, unchanged by the type filter
            assertThat(s1.totalPlay()).isEqualByComparingTo("15");
            assertThat(s1.amount()).isEqualByComparingTo("-11");
            assertThat(s1.playCount()).isEqualTo(2);

            ProjectionResponse<HighLevelEntry> l2 = read(v, AggregationLevel.L2, false, Set.of("PRIZE"), 1, 10);
            SessionView s1l2 = (SessionView) l2.data().get(2);
            assertThat(s1l2.subitems()).extracting(PlayView::subBatchId).containsExactly("p2", "p1");
            assertThat(s1l2.subitems().get(0).txs()).isEmpty();
            assertThat(s1l2.subitems().get(1).txs()).extracting(TxView::txId).containsExactly("s1-b");
            assertThat(s1l2.txs()).isNull();

            // a list that matches no known type: sessions present, every nested list empty
            ProjectionResponse<HighLevelEntry> none = read(v, AggregationLevel.L1, false, Set.of("NO_SUCH_TYPE"), 1, 10);
            assertThat(none.totalRowCount()).isEqualTo(4);
            assertThat(ids(none.data())).containsExactly("s2", "u1", "s1", "u0");
            assertThat(((SessionView) none.data().get(2)).txs()).isEmpty();
            assertThat(((SessionView) none.data().get(2)).totalPlay()).isEqualByComparingTo("15");
        }
    }

    @Test
    void exclude_cancelled_uses_excl_totals_and_hides_cancelled_txs() {
        for (ViewKind v : ViewKind.values()) {
            ProjectionResponse<HighLevelEntry> l1 = read(v, AggregationLevel.L1, true, Set.of(), 1, 10);
            assertThat(ids(l1.data())).containsExactly("s2", "u1", "s1", "u0");
            SessionView s1 = (SessionView) l1.data().get(2);
            assertThat(s1.totalPlay()).isEqualByComparingTo("10");
            assertThat(s1.totalWin()).isEqualByComparingTo("4");
            assertThat(s1.amount()).isEqualByComparingTo("-6");
            assertThat(s1.playCount()).isEqualTo(1);
            // exactly level 0's excludeCancelled rows of the session: cancelled hidden, the cancellation row kept
            List<String> level0 = level0Excl(v).stream()
                    .filter(t -> "s1".equals(t.batchId())).map(TxView::txId).toList();
            assertThat(s1.txs()).extracting(TxView::txId).containsExactlyElementsOf(level0)
                    .containsExactly("s1-d", "x-s1-c", "s1-b", "s1-a");

            ProjectionResponse<HighLevelEntry> l2 = read(v, AggregationLevel.L2, true, Set.of(), 1, 10);
            SessionView s1l2 = (SessionView) l2.data().get(2);
            // p2's only transaction is cancelled: the play is omitted
            assertThat(s1l2.subitems()).extracting(PlayView::subBatchId).containsExactly("p1");
            assertThat(s1l2.subitems().get(0).playAmount()).isEqualByComparingTo("10");
            assertThat(s1l2.subitems().get(0).amount()).isEqualByComparingTo("-6");
        }
    }

    /**
     * F2 (final review): under excl a fully-cancelled play is omitted at level 2, but the
     * cancellation row carrying its subBatchId stays -- on the session, at level 1 and level 2.
     * s3: s3-a TT_BUY 5 (p3) 08:00, x-s3-a cancels it 08:02 (carries p3), s3-b TT_TRANSFER_OUT 1 08:05.
     */
    @Test
    void cancellation_of_an_omitted_play_stays_on_its_session() {
        applyBatches(List.of(
                item("s3-a", "2034-02-11T08:00:00Z", "s3", "p3", TxTypes.PLAY, "5", "keno"),
                cancel("x-s3-a", "2034-02-11T08:02:00Z", "s3", "p3", "s3-a"),
                item("s3-b", "2034-02-11T08:05:00Z", "s3", null, TxTypes.NEUTRAL, "1", "keno")));
        for (ViewKind v : ViewKind.values()) {
            SessionView l1 = session(read(v, AggregationLevel.L1, true, Set.of(), 1, 10), "s3");
            assertThat(l1.txs()).as(v + " L1").extracting(TxView::txId).containsExactly("s3-b", "x-s3-a");

            SessionView l2 = session(read(v, AggregationLevel.L2, true, Set.of(), 1, 10), "s3");
            assertThat(l2.subitems()).as(v + " L2 plays").isEmpty();
            assertThat(l2.txs()).as(v + " L2").extracting(TxView::txId).containsExactly("s3-b", "x-s3-a");

            // not excl: p3 is listed and holds both of its rows
            SessionView all = session(read(v, AggregationLevel.L2, false, Set.of(), 1, 10), "s3");
            assertThat(all.subitems()).extracting(PlayView::subBatchId).containsExactly("p3");
            assertThat(all.subitems().get(0).txs()).extracting(TxView::txId).containsExactly("x-s3-a", "s3-a");
            assertThat(all.txs()).extracting(TxView::txId).containsExactly("s3-b");
        }
    }

    /**
     * F3 (final review): a cancellation dated after its session's endedAt (cancelling the last
     * transaction; cancels never roll up, so endedAt stays) is still one of the session's rows.
     * s4: s4-a TT_BUY 2 08:00, s4-b PRIZE 3 08:10; x-s4-b cancels s4-b at 09:00. A cancel past the
     * range end ({@code TO}) is outside the response.
     */
    @Test
    void late_cancellation_stays_on_its_session_within_the_range() {
        applyBatches(List.of(
                item("s4-a", "2034-02-12T08:00:00Z", "s4", null, TxTypes.PLAY, "2", "keno"),
                item("s4-b", "2034-02-12T08:10:00Z", "s4", null, TxTypes.WIN, "3", "keno"),
                cancel("x-s4-b", "2034-02-12T09:00:00Z", "s4", null, "s4-b"),
                item("s5-a", "2034-02-28T23:00:00Z", "s5", null, TxTypes.PLAY, "2", "keno"),
                cancel("x-s5-a", "2034-03-01T00:30:00Z", "s5", null, "s5-a")));
        for (ViewKind v : ViewKind.values()) {
            for (AggregationLevel level : List.of(AggregationLevel.L1, AggregationLevel.L2)) {
                SessionView s4 = session(read(v, level, false, Set.of(), 1, 10), "s4");
                assertThat(s4.endedAt()).isEqualTo("2034-02-12T08:10:00Z");
                assertThat(s4.txs()).as(v + " " + level).extracting(TxView::txId)
                        .containsExactly("x-s4-b", "s4-b", "s4-a");
                SessionView excl = session(read(v, level, true, Set.of(), 1, 10), "s4");
                assertThat(excl.txs()).as(v + " " + level + " excl").extracting(TxView::txId)
                        .containsExactly("x-s4-b", "s4-a");
                SessionView s5 = session(read(v, level, false, Set.of(), 1, 10), "s5");
                assertThat(s5.txs()).as(v + " " + level + " past TO").extracting(TxView::txId)
                        .containsExactly("s5-a");
            }
        }
    }

    /**
     * Re-review regression: the range end bounds only late cancellations. A session starting in
     * range but ending past {@code TO} lists every non-cancel transaction its totals count, while a
     * cancel dated past {@code TO} (and past "endedAt") stays out. s6: s6-a TT_BUY 2 Feb 28 23:00,
     * s6-b PRIZE 3 Mar 1 00:10 (endedAt, past TO); x-s6-a cancels s6-a Mar 1 00:40.
     */
    @Test
    void session_straddling_the_range_end_lists_all_its_transactions() {
        applyBatches(List.of(
                item("s6-a", "2034-02-28T23:00:00Z", "s6", null, TxTypes.PLAY, "2", "keno"),
                item("s6-b", "2034-03-01T00:10:00Z", "s6", null, TxTypes.WIN, "3", "keno"),
                cancel("x-s6-a", "2034-03-01T00:40:00Z", "s6", null, "s6-a")));
        for (ViewKind v : ViewKind.values()) {
            for (AggregationLevel level : List.of(AggregationLevel.L1, AggregationLevel.L2)) {
                SessionView s6 = session(read(v, level, false, Set.of(), 1, 10), "s6");
                String as = v + " " + level;
                assertThat(s6.endedAt()).isEqualTo("2034-03-01T00:10:00Z");
                assertThat(s6.txs()).as(as).extracting(TxView::txId).containsExactly("s6-b", "s6-a");
                assertThat(s6.totalPlay()).as(as).isEqualByComparingTo(sumOf(s6.txs(), "TT_BUY"));
                assertThat(s6.totalWin()).as(as).isEqualByComparingTo(sumOf(s6.txs(), "PRIZE"));
                assertThat(s6.totalPlay()).isEqualByComparingTo("2");
                assertThat(s6.totalWin()).isEqualByComparingTo("3");
            }
        }
    }

    private static BigDecimal sumOf(List<TxView> txs, String type) {
        return txs.stream().filter(t -> type.equals(t.transactionType()))
                .map(t -> t.amount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * F1 (final review), spec 7.3 "each bounded by the page size": the item fetch reads only the
     * page's sessions' own rows, not every item in the time span the page's sessions cover. A
     * long-lived session (Feb 1 .. Feb 27) is on the last page with four newer entries; 3000
     * unrelated rows lie inside its window (1500 unsessioned items, 1500 one-play sessions). Signal: EXPLAIN (ANALYZE, FORMAT
     * JSON) of every statement the assembler ran, after VACUUM ANALYZE with planner settings
     * unchanged; over every item-table scan node, (actual rows + rows removed by filter / index
     * recheck) x loops -- the rows the executor examined.
     */
    @Test
    void item_fetch_examines_only_the_page_rows() throws Exception {
        reset();
        List<ProjectionItem> data = new ArrayList<>();
        data.add(item("L-a", "2034-02-01T00:00:00Z", "L", "Lp", TxTypes.PLAY, "1", "keno"));
        data.add(item("L-b", "2034-02-27T00:00:00Z", "L", "Lp", TxTypes.WIN, "2", "keno"));
        Instant start = Instant.parse("2034-02-02T00:00:00Z");
        for (int i = 0; i < 3000; i++) {
            String at = start.plusSeconds(600L * i).toString();
            data.add(i % 2 == 0 // every other one is a one-play session of its own (id = its batch id)
                    ? item("n" + i + "-t", at, "n" + i, "np" + i, TxTypes.PLAY, "1", "keno")
                    : item("n" + i, at, null, null, TxTypes.PLAY, "1", "keno"));
        }
        applyBatches(data);
        dsl().execute("vacuum analyze \"wqWebItem\"");
        dsl().execute("vacuum analyze \"wqCcItem\"");
        dsl().execute("vacuum analyze \"wqSession\"");
        dsl().execute("vacuum analyze \"wqPlay\"");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (ViewKind v : ViewKind.values()) {
            HighLevelItemQuery items = v == ViewKind.WEB ? webAssembly : ccAssembly;
            for (AggregationLevel level : List.of(AggregationLevel.L1, AggregationLevel.L2)) {
                StatementRecorder recording = new StatementRecorder(readerDataSource);
                HighLevelAssembler.Nesting n = new HighLevelAssembler.Nesting(level, Set.of(), false, TO);
                // 3001 entries, pageSize 7 -> page 429 holds n3..n0 and L
                HighLevelPage<HighLevelEntry> page = reader.readHighLevel(
                        new HighLevelRequest(v, level, new HighLevelFilter(USER, false), FROM, TO, 429, 7),
                        (tx, keys) -> assembler.assemble(recording.client(), items, USER, n, keys));
                assertThat(ids(page.entries())).containsExactly("n3", "n2", "n1", "n0", "L");
                assertThat(((SessionView) page.entries().get(4)).endedAt()).isEqualTo("2034-02-27T00:00:00Z");
                long examined = 0;
                for (StatementRecorder.Executed q : recording.executed()) {
                    String plan = recording.rerun("explain (analyze, format json)", q).get(0);
                    examined += examinedItemRows(json.readTree(plan));
                }
                assertThat(examined).as(v + " " + level + " item and play rows examined").isLessThanOrEqualTo(40);
            }
        }
    }

    /** (actual rows + rows removed) x loops over every scan node of an item or play table or partition. */
    private static long examinedItemRows(com.fasterxml.jackson.databind.JsonNode node) {
        long sum = 0;
        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode c : node) {
                sum += examinedItemRows(c);
            }
            return sum;
        }
        if (node.has("Plan")) {
            return examinedItemRows(node.get("Plan"));
        }
        String rel = node.path("Relation Name").asText("");
        if (rel.startsWith("wqWebItem") || rel.startsWith("wqCcItem") || rel.startsWith("wqPlay")) {
            long perLoop = node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong()
                    + node.path("Rows Removed by Index Recheck").asLong();
            sum += perLoop * Math.max(1, node.path("Actual Loops").asLong());
        }
        for (com.fasterxml.jackson.databind.JsonNode c : node.path("Plans")) {
            sum += examinedItemRows(c);
        }
        return sum;
    }

    private static SessionView session(ProjectionResponse<HighLevelEntry> r, String batchId) {
        return r.data().stream().filter(e -> e instanceof SessionView s && s.batchId().equals(batchId))
                .map(SessionView.class::cast).findFirst().orElseThrow(() -> new AssertionError("no session " + batchId));
    }

    /** Spec 7.3: at most four queries per page, whatever its size; page 5 and page 50 cost the same. */
    @Test
    void assembly_query_count_is_bounded() {
        List<ProjectionItem> more = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String at = "2034-02-20T%02d:%02d:00Z".formatted(i / 4, (i % 4) * 10);
            String at2 = "2034-02-20T%02d:%02d:05Z".formatted(i / 4, (i % 4) * 10);
            if (i % 2 == 0) {
                more.add(item("m" + i + "-a", at, "ms" + i, "mp" + i, TxTypes.PLAY, "1", "keno"));
                more.add(item("m" + i + "-b", at2, "ms" + i, "mp" + i, TxTypes.WIN, "2", "keno"));
            } else {
                more.add(item("mu" + i, at, null, null, TxTypes.PLAY, "1", "keno"));
            }
        }
        applyBatches(more);
        for (ViewKind v : ViewKind.values()) {
            HighLevelItemQuery items = v == ViewKind.WEB ? webAssembly : ccAssembly;
            for (AggregationLevel level : List.of(AggregationLevel.L1, AggregationLevel.L2)) {
                for (Set<String> types : List.of(Set.<String>of(), Set.of("PRIZE"))) {
                    for (boolean cold : List.of(false, true)) {
                        if (!cold) {
                            read(v, level, false, types, 1, 50); // warm the type registry's cache
                        } else {
                            coolTypeRegistry();
                        }
                        int small = assemblyStatements(v, items, level, types, 5);
                        if (cold) {
                            coolTypeRegistry();
                        }
                        int large = assemblyStatements(v, items, level, types, 50);
                        String as = v + " " + level + " " + types + (cold ? " cold" : " warm");
                        assertThat(small).as(as).isEqualTo(large)
                                .isLessThanOrEqualTo(level == AggregationLevel.L2 ? 4 : 3)
                                .isGreaterThanOrEqualTo(3);
                    }
                }
            }
        }
    }

    /** Empties the shared type registry's caches, so the next lookup of any type reads the table. */
    @SuppressWarnings("unchecked")
    private void coolTypeRegistry() {
        for (String cache : List.of("idsByCode", "byId")) {
            ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(txTypes, cache)).clear();
        }
    }

    /** Statements the assembler ran for page 1 of {@code pageSize}, which must hold both kinds of entry. */
    private int assemblyStatements(ViewKind v, HighLevelItemQuery items, AggregationLevel level,
                                   Set<String> types, int pageSize) {
        // JDBC-level: every statement the assembler sends, joined to the reader transaction.
        StatementRecorder counting = new StatementRecorder(readerDataSource);
        HighLevelAssembler.Nesting n = new HighLevelAssembler.Nesting(level, types, false, TO);
        HighLevelPage<HighLevelEntry> page = reader.readHighLevel(
                new HighLevelRequest(v, level, new HighLevelFilter(USER, false), FROM, TO, 1, pageSize),
                (tx, keys) -> assembler.assemble(counting.client(), items, USER, n, keys));
        assertThat(page.entries()).hasSize(pageSize);
        assertThat(page.entries()).hasAtLeastOneElementOfType(SessionView.class).hasAtLeastOneElementOfType(TxView.class);
        return counting.count();
    }

    private ProjectionResponse<HighLevelEntry> read(ViewKind v, AggregationLevel level, boolean excl,
                                                    Set<String> types, long page, long size) {
        return v == ViewKind.WEB
                ? web.highLevel(USER, level, FROM, TO, excl, Set.of(), types, page, size)
                : cc.highLevel(USER, level, FROM, TO, excl, Set.of(), types, page, size);
    }

    private List<TxView> level0Excl(ViewKind v) {
        return (v == ViewKind.WEB
                ? web.level0(USER, FROM, TO, true, Set.of(), Set.of(), 1, 100)
                : cc.level0(USER, FROM, TO, true, Set.of(), Set.of(), 1, 100)).data();
    }

    static List<String> ids(List<HighLevelEntry> entries) {
        return entries.stream().map(e -> switch (e) {
            case SessionView s -> s.batchId();
            case TxView t -> t.txId();
        }).toList();
    }

    private static ProjectionItem item(String id, String at, String batchId, String subBatchId, short type,
                                       String amount, String productType) {
        return new ProjectionItem(USER, SourceKind.TRANSACTION, id, Instant.parse(at),
                productType == null ? null : "P-" + productType, productType, batchId, subBatchId, type,
                new BigDecimal(amount), "EUR", null, null, null);
    }

    private static ProjectionItem cancel(String id, String at, String batchId, String target) {
        return cancel(id, at, batchId, null, target);
    }

    private static ProjectionItem cancel(String id, String at, String batchId, String subBatchId, String target) {
        return new ProjectionItem(USER, SourceKind.TRANSACTION, id, Instant.parse(at), null, null, batchId, subBatchId,
                TxTypes.CANCEL, new BigDecimal("-5"), "EUR", null, target, null);
    }
}
