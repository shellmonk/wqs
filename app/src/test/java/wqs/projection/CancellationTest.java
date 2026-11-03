package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

/**
 * Cancellation (spec 6.3): marking the original, subtracting its own contribution from the
 * ExclCancelled rollups and counters, per-view page-index visibility, revival, and parking of
 * cancels that arrive before their original.
 */
@SpringBootTest
class CancellationTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2035-02-12T13:00:00Z");
    private static final int WEB = 1;
    private static final int CC = 2;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private PendingCancelStore pendingCancelStore;
    @Autowired private PartitionManager partitionManager;
    @Autowired private TransactionTemplate transactionTemplate;

    private long offset;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2035, 2), 0);
        clearProjection();
    }

    // --- the brief's six cases, with per-view visibility ---------------------------------------

    @Test
    void cancelling_one_of_two_transactions_leaves_the_session_visible() {
        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));

        apply(cancelOf("c1", "t1"));

        assertThat(cancelled(WEB, "t1")).isTrue();
        assertThat(cancelled(CC, "t1")).isTrue();
        assertThat(cancelledBy("t1")).isEqualTo("c1");
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionField("txCount")).isEqualTo(2);
        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        // The session is still a page unit when cancellations are excluded.
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isEqualTo(1);
        // TRANSACTION: t1 left the excl tally; t2 and the cancel row itself remain.
        assertThat(transactionSentinel(WEB, "cntExclCancelled")).isEqualTo(2);
        assertThat(transactionSentinel(WEB, "cntAll")).isEqualTo(3);
        assertNoDrift();
    }

    @Test
    void cancelling_every_transaction_removes_the_session_from_the_excl_cancelled_tally() {
        apply(tx("t1", "b1", "p1"));

        apply(cancelOf("c1", "t1"));

        assertThat(sessionField("txCountExclCancelled")).isZero();
        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isFalse();
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isZero();
        // cntAll is untouched: the row still exists and is returned when the filter is off.
        assertThat(highLevelSentinel(WEB, "cntAll")).isEqualTo(1);
        assertNoDrift();
    }

    @Test
    void cancelling_a_plays_last_transaction_decrements_the_session_play_count() {
        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));

        apply(cancelOf("c1", "t1"));

        assertThat(sessionField("playCount")).isEqualTo(2);
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(1);
        assertThat(playField("p1", "txCount")).isEqualTo(1);
        assertThat(playField("p1", "txCountExclCancelled")).isZero();
        // t1 was a PLAY of 5: the ExclCancelled money loses exactly that contribution.
        assertThat(playMoney("p1", "totalPlaysExclCancelled")).isEqualByComparingTo("0");
        assertThat(playMoney("p1", "netExclCancelled")).isEqualByComparingTo("0");
        assertThat(playMoney("p1", "totalPlays")).isEqualByComparingTo("5");
        assertThat(sessionMoney("totalPlaysExclCancelled")).isEqualByComparingTo("5");
        assertThat(sessionMoney("netExclCancelled")).isEqualByComparingTo("-5");
        assertThat(sessionMoney("totalPlays")).isEqualByComparingTo("10");
    }

    @Test
    void cancelling_a_win_subtracts_it_from_wins_not_plays() {
        apply(tx("t1", "b1", "p1"), txTyped("w1", "b1", "p1", TxTypes.WIN, "-7.0000"));

        apply(cancelOf("c1", "w1"));

        assertThat(playField("p1", "txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(1);
        assertThat(sessionMoney("totalWinsExclCancelled")).isEqualByComparingTo("0");
        assertThat(sessionMoney("totalPlaysExclCancelled")).isEqualByComparingTo("5");
        assertThat(sessionMoney("netExclCancelled")).isEqualByComparingTo("-5");
        assertThat(sessionMoney("totalWins")).isEqualByComparingTo("7");
    }

    @Test
    void an_unsessioned_transaction_drops_out_of_the_excl_cancelled_tally_immediately() {
        apply(payment("pay1", T0));

        apply(new ProjectionTransaction(1L, SourceKind.PAYMENT, "payc", T0.plusSeconds(60), null,
                null, null, null, TxTypes.CANCEL, new BigDecimal("-20.0000"), "EUR", null, "pay1", null));

        assertThat(rowVisible(CC, SourceKind.PAYMENT.code(), "pay1")).isFalse();
        assertThat(highLevelSentinel(CC, "cntExclCancelled")).isZero();
        assertThat(highLevelSentinel(CC, "cntAll")).isEqualTo(1);
        assertNoDrift();
    }

    @Test
    void a_cancel_arriving_before_its_original_is_parked_then_applied() {
        apply(cancelOf("c1", "t1"));

        assertThat(pendingCancelStore.depth()).isEqualTo(1);
        assertThat(pendingCancelStore.oldestParkedAt()).isPresent();
        assertThat(exists(WEB, "t1")).isFalse();
        assertNoDrift();

        apply(tx("t1", "b1", "p1"));

        assertThat(pendingCancelStore.depth()).isZero();
        assertThat(pendingCancelStore.oldestParkedAt()).isEmpty();
        assertThat(cancelled(WEB, "t1")).isTrue();
        assertThat(sessionField("txCountExclCancelled")).isZero();
        assertThat(sessionVisible(WEB)).isFalse();
        assertNoDrift();
    }

    @Test
    void the_same_cancel_applied_twice_does_not_double_decrement() {
        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));
        ProjectionBatch cancel = new ProjectionBatch("s", List.of(cancelOf("c1", "t1")), 2L);

        writer.apply(cancel);
        writer.apply(cancel);

        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(1);
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isEqualTo(1);
        assertNoDrift();
    }

    // --- replay and second cancels ---------------------------------------------------------------

    @Test
    void a_replayed_parked_cancel_is_applied_once() {
        apply(cancelOf("c1", "t1"));
        apply(cancelOf("c1", "t1"));
        assertThat(pendingCancelStore.depth()).isEqualTo(1);

        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));
        apply(cancelOf("c1", "t1"));

        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertThat(transactionSentinel(WEB, "cntExclCancelled")).isEqualTo(2);
        assertNoDrift();
    }

    @Test
    void a_second_distinct_cancel_of_the_same_transaction_changes_no_tally_and_converges_on_cancelledBy() {
        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));

        apply(cancelOf("c2", "t1"));
        apply(cancelOf("c1", "t1"));

        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        // Whichever arrives first, the smallest cancel id is recorded (P-1).
        assertThat(cancelledBy("t1")).isEqualTo("c1");
        assertNoDrift();
    }

    @Test
    void two_cancels_in_either_order_record_the_smallest_cancel_id() {
        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));

        apply(cancelOf("c1", "t1"));
        apply(cancelOf("c2", "t1"));

        // LEAST, not last-wins: c2 arrived last yet c1 is kept.
        assertThat(cancelledBy("t1")).isEqualTo("c1");
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertNoDrift();
    }

    // --- R96: a cancel only resolves an original of its own user ---------------------------------

    @Test
    void a_cancel_from_another_user_stays_parked_and_changes_nothing() {
        apply(tx("t1", "b1", "p1"));
        ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());

        apply(cancelOfUser(2L, "c1", "t1"));

        assertThat(pendingCancelStore.depth()).isEqualTo(1);
        assertThat(pendingCancelStore.oldestParkedAt()).isPresent();
        assertThat(cancelled(WEB, "t1")).isFalse();
        ProjectionSnapshot after = ProjectionSnapshot.of(jdbc());
        // User 1's projection is untouched: rollups and page index are identical.
        assertThat(after.sessions()).isEqualTo(before.sessions());
        assertThat(after.plays()).isEqualTo(before.plays());
        assertThat(after.timeline()).isEqualTo(before.timeline());
        assertThat(cancelledBy("t1")).isNull();
        assertNoDrift();
    }

    @Test
    void a_parked_cancel_from_another_user_is_not_drained_by_the_original() {
        apply(cancelOfUser(2L, "c1", "t1"));

        apply(tx("t1", "b1", "p1"));

        assertThat(pendingCancelStore.depth()).isEqualTo(1);
        assertThat(cancelled(WEB, "t1")).isFalse();
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionVisible(WEB)).isTrue();
        assertNoDrift();
    }

    // --- R86: resolution by (source, sourceId) ---------------------------------------------------

    @Test
    void a_cancel_resolves_its_original_by_source_not_by_source_id_alone() {
        apply(tx("x1", "b1", "p1"), payment("x1", T0.plusSeconds(5)));

        apply(new ProjectionTransaction(1L, SourceKind.PAYMENT, "payc", T0.plusSeconds(60), null,
                null, null, null, TxTypes.CANCEL, new BigDecimal("-20.0000"), "EUR", null, "x1", null));

        assertThat(cancelledOf(CC, SourceKind.PAYMENT, "x1")).isTrue();
        assertThat(cancelledOf(CC, SourceKind.TRANSACTION, "x1")).isFalse();
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(rowVisible(CC, SourceKind.PAYMENT.code(), "x1")).isFalse();
        assertNoDrift();
    }

    @Test
    void a_parked_cancel_is_drained_only_by_an_transaction_of_its_own_source() {
        apply(cancelOf("c1", "x1"));

        apply(payment("x1", T0.plusSeconds(5)));

        assertThat(pendingCancelStore.depth()).isEqualTo(1);
        assertThat(cancelledOf(CC, SourceKind.PAYMENT, "x1")).isFalse();

        apply(tx("x1", "b1", "p1"));

        assertThat(pendingCancelStore.depth()).isZero();
        assertThat(cancelledOf(CC, SourceKind.TRANSACTION, "x1")).isTrue();
        assertThat(cancelledOf(CC, SourceKind.PAYMENT, "x1")).isFalse();
        assertNoDrift();
    }

    @Test
    void a_parked_cancel_is_rebuilt_faithfully_with_its_real_source() {
        ProjectionTransaction cancel = new ProjectionTransaction(7L, SourceKind.PAYMENT, "pc1", T0, "P04", "keno", "b9",
                "p9", TxTypes.CANCEL, new BigDecimal("-3.5000"), "USD", new BigDecimal("1.2500"),
                "pay9", "{\"why\": \"chargeback\"}");
        writer.apply(new ProjectionBatch("s", List.of(cancel), 1L));

        // Same sourceId, wrong source: not a match.
        assertThat(drain(7L, SourceKind.TRANSACTION, "pay9")).isEmpty();
        // Right source, wrong user: not a match either.
        assertThat(drain(8L, SourceKind.PAYMENT, "pay9")).isEmpty();
        assertThat(drain(7L, SourceKind.PAYMENT, "pay9")).containsExactly(cancel);
        assertThat(pendingCancelStore.depth()).isZero();
    }

    // --- R87: one visibility for both endpoints --------------------------------------------------

    /** A session never mixes sources, so a surviving transaction keeps it visible on both endpoints. */
    @Test
    void cancelling_one_transaction_of_a_session_with_a_surviving_prize_keeps_it_visible_on_both() {
        apply(tx("t1", "b1", "p1"), prize("a1", "b1", T0.plusSeconds(30), 11));

        apply(cancelOf("c1", "t1"));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isEqualTo(1);
        assertThat(highLevelSentinel(CC, "cntExclCancelled")).isEqualTo(1);
        // The prize still survives in the session's rollup.
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertNoDrift();
    }

    // --- R88: revival ----------------------------------------------------------------------------

    @Test
    void a_live_transaction_joining_an_emptied_session_revives_its_rows() {
        apply(tx("t1", "b1", "p1"));
        apply(cancelOf("c1", "t1"));
        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isFalse();

        apply(tx("t2", "b1", "p2"));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isEqualTo(1);
        assertThat(highLevelSentinel(WEB, "cntAll")).isEqualTo(1);
        assertNoDrift();
    }

    @Test
    void a_claimable_prize_revives_the_row_on_both_endpoints() {
        apply(tx("t1", "b1", "p1"));
        apply(cancelOf("c1", "t1"));

        apply(prize("a1", "b1", T0.plusSeconds(30), 11));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isEqualTo(1);
        assertThat(highLevelSentinel(CC, "cntExclCancelled")).isEqualTo(1);
        assertNoDrift();
    }

    @Test
    void a_live_transaction_joining_an_emptied_play_restores_the_session_play_count() {
        apply(tx("t1", "b1", "p1"), tx("t2", "b1", "p2"));
        apply(cancelOf("c1", "t1"));
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(1);

        apply(txAt("t3", "b1", "p1", T0.plusSeconds(40)));

        assertThat(playField("p1", "txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionField("playCount")).isEqualTo(2);
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(2);
    }

    // --- cancels and re-anchoring ----------------------------------------------------------------

    @Test
    void a_cancelled_session_that_later_re_anchors_keeps_its_counters_consistent() {
        apply(txProduct("t1", "b1", "p1", T0, 11));
        apply(cancelOf("c1", "t1"));
        assertThat(sessionVisible(WEB)).isFalse();
        assertNoDrift();

        // Earlier by a day at a lower product: start AND product move, the hidden row moves with
        // its visibility, and the live prize revives it at the new key on both endpoints.
        apply(prize("a0", "b1", T0.minus(1, ChronoUnit.DAYS), 7));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(sessionStart()).isEqualTo(T0.minus(1, ChronoUnit.DAYS));
        assertThat(highLevelProduct(WEB)).isEqualTo("P07");
        assertNoDrift();

        // At the session's start with a lower sourceId than a0 ("a" < "a0"): it is now the first
        // transaction (D6), so a product-only change.
        apply(txProduct("a", "b1", "p9", T0.minus(1, ChronoUnit.DAYS), 3));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(highLevelProduct(WEB)).isEqualTo("P03");
        assertNoDrift();

        // Cancelling within the re-anchored session leaves the prize, so the row stays visible.
        apply(cancelOf("c9", "a"));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertNoDrift();
    }

    // --- R90: order independence -----------------------------------------------------------------

    @Test
    void every_order_of_a_cancel_its_original_and_a_prize_converges() {
        // t1 and t2 in different plays; a1 is a prize a day earlier at a lower product, so it
        // re-anchors the session (start and product) whenever it arrives after the others.
        List<ProjectionTransaction> transactions = List.of(
                txProduct("t1", "b1", "p1", T0, 11),
                txProduct("t2", "b1", "p2", T0.plusSeconds(10), 11),
                cancelOf("c1", "t1"),
                prize("a1", "b1", T0.minus(1, ChronoUnit.DAYS), 7));

        ProjectionSnapshot expected = assertAllOrdersConverge(transactions);

        assertThat(expected.pendingCancels()).isEmpty();
        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(1);
    }

    @Test
    void every_order_of_a_cancel_of_a_sessions_only_transaction_and_a_late_earlier_one_converges() {
        // a0 re-anchors the session whenever it arrives after t1; c1 hides it until a0 arrives.
        List<ProjectionTransaction> transactions = List.of(
                txProduct("t1", "b1", "p1", T0, 11),
                cancelOf("c1", "t1"),
                txProduct("a0", "b1", "p2", T0.minus(1, ChronoUnit.DAYS), 7));

        assertAllOrdersConverge(transactions);

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(sessionStart()).isEqualTo(T0.minus(1, ChronoUnit.DAYS));
    }

    /**
     * Applies every permutation into clean state, once one projected transaction per batch (cancel and original in
     * different transactions: the before case parks) and once as a single batch (parked and
     * drained within one transaction), and asserts every resulting snapshot is identical and
     * consistent. Leaves the last run's state in place.
     */
    private ProjectionSnapshot assertAllOrdersConverge(List<ProjectionTransaction> transactions) {
        ProjectionSnapshot expected = null;
        for (List<ProjectionTransaction> order : permutations(transactions)) {
            for (boolean singleBatch : List.of(false, true)) {
                clearProjection();
                if (singleBatch) {
                    writer.apply(new ProjectionBatch("s", order, ++offset));
                } else {
                    for (ProjectionTransaction transaction : order) {
                        apply(transaction);
                    }
                }
                ProjectionSnapshot snapshot = ProjectionSnapshot.of(jdbc());
                String described = order.stream().map(ProjectionTransaction::sourceId).toList()
                        + (singleBatch ? " as one batch" : " one per batch");
                assertNoDrift();
                if (expected == null) {
                    expected = snapshot;
                } else {
                    assertThat(snapshot).as(described).isEqualTo(expected);
                }
            }
        }
        return expected;
    }

    private static <T> List<List<T>> permutations(List<T> transactions) {
        if (transactions.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i++) {
            List<T> rest = new ArrayList<>(transactions);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> order = new ArrayList<>();
                order.add(head);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }

    /** Counters, rollups and the page index all agree with the transaction rows. */
    private void assertNoDrift() {
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    // --- fixtures --------------------------------------------------------------------------------

    private void apply(ProjectionTransaction... transactions) {
        writer.apply(new ProjectionBatch("s", List.of(transactions), ++offset));
    }

    private List<ProjectionTransaction> drain(long userId, SourceKind source, String sourceId) {
        // drainFor joins the projection transaction (MANDATORY); supply one, as the writer does.
        return transactionTemplate.execute(status ->
                pendingCancelStore.drainFor(Set.of(new TransactionRef(userId, source, sourceId))));
    }

    private ProjectionTransaction tx(String id, String batchId, String subBatchId) {
        return txAt(id, batchId, subBatchId, T0);
    }

    private ProjectionTransaction txAt(String id, String batchId, String subBatchId, Instant at) {
        return txProduct(id, batchId, subBatchId, at, 11);
    }

    private ProjectionTransaction txProduct(
            String id, String batchId, String subBatchId, Instant at, int product) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, at, Products.id(product), Products.type(product),
                batchId, subBatchId, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction txTyped(
            String id, String batchId, String subBatchId, String type, String amount) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, T0.plusSeconds(1), "P11", "instants", batchId, subBatchId,
                type, new BigDecimal(amount), "EUR", null, null, null);
    }

    private ProjectionTransaction cancelOf(String id, String cancels) {
        return cancelOfUser(1L, id, cancels);
    }

    private ProjectionTransaction cancelOfUser(long userId, String id, String cancels) {
        return new ProjectionTransaction(
                userId, SourceKind.TRANSACTION, id, T0.plusSeconds(120), "P13", "arcade", null, null,
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, cancels, null);
    }

    private ProjectionTransaction prize(String id, String batchId, Instant at, int product) {
        return new ProjectionTransaction(
                1L, SourceKind.CLAIMABLE_PRIZE, id, at, Products.id(product), Products.type(product),
                batchId, null, TxTypes.WIN, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction payment(String id, Instant at) {
        return new ProjectionTransaction(
                1L, SourceKind.PAYMENT, id, at, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null);
    }

    private static String transactionTable(int view) {
        return view == WEB ? "wqPrjWebTransaction" : "wqPrjCcTransaction";
    }

    private static String pageTable(int view) {
        return ProjectionInvariants.relation(view == WEB ? ProjectionInvariants.WEB_TIMELINE : ProjectionInvariants.CC_TIMELINE);
    }

    private boolean cancelled(int view, String sourceId) {
        return cancelledOf(view, SourceKind.TRANSACTION, sourceId);
    }

    private boolean cancelledOf(int view, SourceKind source, String sourceId) {
        return db().value(
                "select \"cancelled\" from \"" + transactionTable(view)
                        + "\" where \"source\" = ? and \"sourceId\" = ?", Boolean.class,
                (short) source.code(), sourceId);
    }

    private String cancelledBy(String sourceId) {
        return db().value(
                "select \"cancelledBy\" from \"wqPrjCcTransaction\" where \"source\" = 1 and \"sourceId\" = ?", String.class,
                sourceId);
    }

    private boolean exists(int view, String sourceId) {
        return db().value("select count(*)::int from \"" + transactionTable(view)
                + "\" where \"sourceId\" = ?", Integer.class, sourceId) > 0;
    }

    private int sessionField(String field) {
        return db().value("select \"" + field + "\" from \"wqPrjSession\"", Integer.class);
    }

    private BigDecimal sessionMoney(String field) {
        return db().value("select \"" + field + "\" from \"wqPrjSession\"", BigDecimal.class);
    }

    private Instant sessionStart() {
        return db().value("select \"startedAt\" from \"wqPrjSession\"", Instant.class);
    }

    private int playField(String subBatchId, String field) {
        return db().value("select \"" + field + "\" from \"wqPrjPlay\" where \"subBatchId\" = ?", Integer.class,
                subBatchId);
    }

    private BigDecimal playMoney(String subBatchId, String field) {
        return db().value("select \"" + field + "\" from \"wqPrjPlay\" where \"subBatchId\" = ?", BigDecimal.class,
                subBatchId);
    }

    private boolean sessionVisible(int view) {
        return db().value("select \"visibleExclCancelled\" from " + pageTable(view)
                + " where \"kind\" = 0", Boolean.class);
    }

    private String highLevelProduct(int view) {
        return db().value("select \"productId\" from " + pageTable(view)
                + " where \"kind\" = 0", String.class);
    }

    private boolean rowVisible(int view, int kind, String refId) {
        return db().value("select \"visibleExclCancelled\" from " + pageTable(view)
                + " where \"kind\" = ? and \"refId\" = ?", Boolean.class, (short) kind, refId);
    }

    private int highLevelSentinel(int view, String column) {
        return sentinel(view, 1, column);
    }

    private int transactionSentinel(int view, String column) {
        return sentinel(view, 0, column);
    }

    private int sentinel(int view, int pageUnit, String column) {
        return db().value("""
                select coalesce(sum("%s"),0)::int from "wqPrjCountDay"
                where "viewKind" = ? and "pageUnit" = ? and "productType" = '' and "type" = ''
                """.formatted(column), Integer.class, (short) view, (short) pageUnit);
    }
}
