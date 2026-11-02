package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
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
                    read(v, level, false, types, 1, 50); // warm the type registry's cache
                    int small = assemblyStatements(v, items, level, types, 5);
                    int large = assemblyStatements(v, items, level, types, 50);
                    String as = v + " " + level + " " + types;
                    assertThat(small).as(as).isEqualTo(large)
                            .isLessThanOrEqualTo(level == AggregationLevel.L2 ? 4 : 3)
                            .isGreaterThanOrEqualTo(3);
                }
            }
        }
    }

    /** Statements the assembler ran for page 1 of {@code pageSize}, which must hold both kinds of entry. */
    private int assemblyStatements(ViewKind v, HighLevelItemQuery items, AggregationLevel level,
                                   Set<String> types, int pageSize) {
        AtomicInteger statements = new AtomicInteger();
        ExecuteListener counting = new ExecuteListener() {
            @Override
            public void executeStart(ExecuteContext ctx) {
                statements.incrementAndGet();
            }
        };
        HighLevelAssembler.Nesting n = new HighLevelAssembler.Nesting(level, types, false);
        HighLevelPage<HighLevelEntry> page = reader.readHighLevel(
                new HighLevelRequest(v, level, new HighLevelFilter(USER, false), FROM, TO, 1, pageSize),
                (tx, keys) -> {
                    DSLContext observed = DSL.using(tx.configuration().derive(new DefaultExecuteListenerProvider(counting)));
                    return assembler.assemble(observed, items, USER, n, keys);
                });
        assertThat(page.entries()).hasSize(pageSize);
        assertThat(page.entries()).hasAtLeastOneElementOfType(SessionView.class).hasAtLeastOneElementOfType(TxView.class);
        return statements.get();
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
        return new ProjectionItem(USER, SourceKind.TRANSACTION, id, Instant.parse(at), null, null, batchId, null,
                TxTypes.CANCEL, new BigDecimal("-5"), "EUR", null, target, null);
    }
}
