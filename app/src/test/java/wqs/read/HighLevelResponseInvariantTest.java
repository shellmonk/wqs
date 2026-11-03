package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionInvariants;
import wqs.read.cc.CcProjectionService;
import wqs.read.web.WebProjectionService;

/**
 * P-4 at the response (spec 8.1): on one fixed dataset, for both views, every filter below and
 * page sizes 1, 2, 3 and 7, a full walk of level 2 and of level 1 satisfies
 * <ul>
 *   <li>per session: {@code totalPlay = Σ play.playAmount}, {@code totalWin = Σ play.winAmount},
 *       {@code amount = totalWin - totalPlay}, {@code playCount} = the plays listed; per play:
 *       {@code amount = winAmount - playAmount};</li>
 *   <li>without a type filter, the figures also equal the sums over the nested transactions
 *       (TT_BUY plays, PRIZE wins, abs(amount)) -- all of them, or the surviving ones under
 *       excludeCancelled;</li>
 *   <li>levels 1 and 2 list the same top-level entries in the same order, with equal
 *       {@code totalRowCount} and {@code pagesAvailable} on every page.</li>
 * </ul>
 *
 * <p>The dataset (Feb 2034, user 1, built by fixed arithmetic, no randomness): 12 sessions of 0-3
 * plays of 1-3 PLAY/WIN transactions, a play-less NEUTRAL transaction in every third session (one
 * session has only that), cancels inside sessions (one play fully cancelled, one session left
 * visible only by its play-less transaction), three product types, and unsessioned transactions,
 * prizes and payments interleaved and tied with session starts.
 */
@SpringBootTest
class HighLevelResponseInvariantTest extends ReadPropertyBase {

    private static final long USER = 1L;
    private static final Instant FROM = Instant.parse("2034-02-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2034-03-01T00:00:00Z");
    private static final String[] PRODUCTS = {"keno", "lottery", "instants"};

    private record Filter(boolean excl, Set<String> productTypes, Set<String> transactionTypes) {}

    private static final List<Filter> FILTERS = List.of(
            new Filter(false, Set.of(), Set.of()),
            new Filter(true, Set.of(), Set.of()),
            new Filter(false, Set.of("keno", "instants"), Set.of()),
            new Filter(true, Set.of("lottery"), Set.of()),
            new Filter(false, Set.of(), Set.of("PRIZE")),
            new Filter(true, Set.of(), Set.of("TT_BUY", "NO_SUCH_TYPE")));

    @Autowired WebProjectionService web;
    @Autowired CcProjectionService cc;

    @BeforeEach
    void seed() {
        reset();
        applyBatches(dataset());
    }

    /** Guards the dataset: cancellations exist and change totals, so the all/excl choice is observable. */
    @Test
    void dataset_is_clean_and_has_cancelled_plays() {
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
        List<HighLevelEntry> all = walk(ViewKind.CC, AggregationLevel.L2, FILTERS.get(0), 50);
        List<HighLevelEntry> excl = walk(ViewKind.CC, AggregationLevel.L2, FILTERS.get(1), 50);
        assertThat(all.stream().filter(SessionView.class::isInstance)).hasSize(13);
        assertThat(all.stream().filter(TxView.class::isInstance).count()).isGreaterThanOrEqualTo(8);
        long changed = 0;
        for (HighLevelEntry e : excl) {
            if (e instanceof SessionView s) {
                SessionView a = (SessionView) all.stream()
                        .filter(x -> x instanceof SessionView y && y.batchId().equals(s.batchId())).findFirst().orElseThrow();
                if (a.totalPlay().compareTo(s.totalPlay()) != 0 || a.subitems().size() != s.subitems().size()) {
                    changed++;
                }
            }
        }
        assertThat(changed).as("sessions whose excl figures differ").isGreaterThanOrEqualTo(2);
    }

    @Test
    void session_totals_equal_their_plays_and_transactions_at_every_page_size() {
        int sessionsChecked = 0;
        for (ViewKind v : ViewKind.values()) {
            for (Filter f : FILTERS) {
                for (int size : new int[] {1, 2, 3, 7}) {
                    String as = v + " " + f + " size=" + size;
                    List<HighLevelEntry> l2 = walk(v, AggregationLevel.L2, f, size);
                    List<HighLevelEntry> l1 = walk(v, AggregationLevel.L1, f, size);
                    assertThat(HighLevelAssemblyTest.ids(l1)).as(as).doesNotHaveDuplicates()
                            .isEqualTo(HighLevelAssemblyTest.ids(l2));
                    for (int i = 0; i < l2.size(); i++) {
                        if (l2.get(i) instanceof SessionView s2) {
                            SessionView s1 = (SessionView) l1.get(i);
                            checkSession(as + " " + s2.batchId(), s1, s2, f);
                            sessionsChecked++;
                        }
                    }
                }
            }
        }
        assertThat(sessionsChecked).isGreaterThan(200);
    }

    private static void checkSession(String as, SessionView l1, SessionView l2, Filter f) {
        // the session's figures do not depend on the level
        assertThat(l1.totalPlay()).as(as).isEqualByComparingTo(l2.totalPlay());
        assertThat(l1.totalWin()).as(as).isEqualByComparingTo(l2.totalWin());
        assertThat(l1.amount()).as(as).isEqualByComparingTo(l2.amount());
        assertThat(l1.playCount()).as(as).isEqualTo(l2.playCount());

        BigDecimal plays = BigDecimal.ZERO;
        BigDecimal wins = BigDecimal.ZERO;
        List<TxView> l2Txs = new ArrayList<>(l2.txs() == null ? List.of() : l2.txs());
        for (PlayView p : l2.subitems()) {
            plays = plays.add(p.playAmount());
            wins = wins.add(p.winAmount());
            assertThat(p.amount()).as(as + "/" + p.subBatchId()).isEqualByComparingTo(p.winAmount().subtract(p.playAmount()));
            if (f.transactionTypes().isEmpty()) {
                assertThat(sum(p.txs(), "TT_BUY")).as(as + "/" + p.subBatchId() + " plays").isEqualByComparingTo(p.playAmount());
                assertThat(sum(p.txs(), "PRIZE")).as(as + "/" + p.subBatchId() + " wins").isEqualByComparingTo(p.winAmount());
            }
            l2Txs.addAll(p.txs());
        }
        assertThat(l2.totalPlay()).as(as + " = sum of plays").isEqualByComparingTo(plays);
        assertThat(l2.totalWin()).as(as + " = sum of wins").isEqualByComparingTo(wins);
        assertThat(l2.amount()).as(as + " net").isEqualByComparingTo(l2.totalWin().subtract(l2.totalPlay()));
        assertThat(l2.playCount()).as(as + " playCount").isEqualTo(l2.subitems().size());
        // the same transactions are nested at both levels
        assertThat(l2Txs).extracting(TxView::txId).as(as).containsExactlyInAnyOrderElementsOf(
                l1.txs().stream().map(TxView::txId).toList());
        if (f.transactionTypes().isEmpty()) {
            assertThat(sum(l1.txs(), "TT_BUY")).as(as + " = sum of nested plays").isEqualByComparingTo(l1.totalPlay());
            assertThat(sum(l1.txs(), "PRIZE")).as(as + " = sum of nested wins").isEqualByComparingTo(l1.totalWin());
        }
        if (f.excl()) {
            assertThat(l1.txs()).as(as).noneMatch(TxView::cancelled);
        }
    }

    /** Every page of a level, concatenated; totals and pagesAvailable must agree with level 1's on every page. */
    private List<HighLevelEntry> walk(ViewKind v, AggregationLevel level, Filter f, int size) {
        List<HighLevelEntry> all = new ArrayList<>();
        ProjectionResponse<HighLevelEntry> first = read(v, level, f, 1, size);
        ProjectionResponse<HighLevelEntry> firstL1 = read(v, AggregationLevel.L1, f, 1, size);
        assertThat(first.totalRowCount()).isEqualTo(firstL1.totalRowCount());
        assertThat(first.pagesAvailable()).isEqualTo(firstL1.pagesAvailable());
        for (long p = 1; p <= first.pagesAvailable() + 1; p++) {
            ProjectionResponse<HighLevelEntry> r = p == 1 ? first : read(v, level, f, p, size);
            assertThat(r.pagesAvailable()).isEqualTo(first.pagesAvailable());
            assertThat(r.totalRowCount()).isEqualTo(first.totalRowCount());
            all.addAll(r.data());
        }
        assertThat((long) all.size()).isEqualTo(first.totalRowCount());
        return all;
    }

    private ProjectionResponse<HighLevelEntry> read(ViewKind v, AggregationLevel level, Filter f, long page, long size) {
        return v == ViewKind.WEB
                ? web.highLevel(USER, level, FROM, TO, f.excl(), f.productTypes(), f.transactionTypes(), page, size)
                : cc.highLevel(USER, level, FROM, TO, f.excl(), f.productTypes(), f.transactionTypes(), page, size);
    }

    private static BigDecimal sum(List<TxView> txs, String code) {
        return txs.stream().filter(t -> code.equals(t.transactionType()))
                .map(t -> t.amount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ the dataset

    private static List<ProjectionTransaction> dataset() {
        List<ProjectionTransaction> d = new ArrayList<>();
        Instant base = Instant.parse("2034-02-03T08:00:00Z");
        for (int s = 0; s < 12; s++) {
            Instant start = base.plus(Duration.ofHours(37L * s));
            String batch = "s" + s;
            String product = PRODUCTS[s % 3];
            int playsInSession = s == 11 ? 0 : s % 3 + 1;
            int minute = 0;
            for (int p = 0; p < playsInSession; p++) {
                int txs = (s + p) % 3 + 1;
                for (int t = 0; t < txs; t++) {
                    String id = batch + "-p" + p + "-t" + t;
                    boolean win = t % 2 == 1;
                    d.add(transaction(SourceKind.TRANSACTION, id, start.plus(Duration.ofMinutes(minute++)), product, batch,
                            batch + "-p" + p, win ? TxTypes.WIN : TxTypes.PLAY, (s + 2 * p + 3 * t) % 7 + 1));
                    // cancels: a fully cancelled play (s2-p1) and scattered single cancels
                    if ((s == 2 && p == 1) || (s + p + t) % 5 == 0) {
                        d.add(cancel(id + "-x", start.plus(Duration.ofMinutes(minute++)), batch, id));
                    }
                }
            }
            if (s % 3 == 2 || s == 11) {
                d.add(transaction(SourceKind.TRANSACTION, batch + "-loose", start.plus(Duration.ofMinutes(minute++)), product,
                        batch, null, TxTypes.NEUTRAL, 3));
            }
            // unsessioned peers: one tied with the session start, one between sessions (no eInstant: P-7)
            String peerProduct = s % 3 == 2 ? "keno" : product;
            d.add(transaction(s % 2 == 0 ? SourceKind.TRANSACTION : SourceKind.CLAIMABLE_PRIZE, "u" + s, start, peerProduct,
                    null, null, s % 2 == 0 ? TxTypes.PLAY : TxTypes.WIN, 2));
            d.add(transaction(SourceKind.PAYMENT, "y" + s, start.plus(Duration.ofHours(20)), null, null, null, TxTypes.NEUTRAL, 9));
        }
        // a session kept visible only by its play-less transaction: every play transaction cancelled
        Instant last = Instant.parse("2034-02-27T08:00:00Z");
        d.add(transaction(SourceKind.TRANSACTION, "sv-t0", last, "keno", "sv", "sv-p0", TxTypes.PLAY, 4));
        d.add(cancel("sv-t0-x", last.plus(Duration.ofMinutes(1)), "sv", "sv-t0"));
        d.add(transaction(SourceKind.TRANSACTION, "sv-loose", last.plus(Duration.ofMinutes(2)), "keno", "sv", null,
                TxTypes.NEUTRAL, 1));
        return d;
    }

    private static ProjectionTransaction transaction(SourceKind source, String id, Instant at, String productType, String batchId,
                                       String subBatchId, String type, int amount) {
        return new ProjectionTransaction(USER, source, id, at, productType == null ? null : "P-" + productType, productType,
                batchId, subBatchId, type, new BigDecimal(amount), "EUR", null, null, null);
    }

    private static ProjectionTransaction cancel(String id, Instant at, String batchId, String target) {
        return new ProjectionTransaction(USER, SourceKind.TRANSACTION, id, at, null, null, batchId, null,
                TxTypes.CANCEL, new BigDecimal("-1"), "EUR", null, target, null);
    }
}
