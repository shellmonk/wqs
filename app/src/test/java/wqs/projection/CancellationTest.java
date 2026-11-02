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
import wqs.domain.ProjectionItem;
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
        // ITEM: t1 left the excl tally; t2 and the cancel row itself remain.
        assertThat(itemSentinel(WEB, "cntExclCancelled")).isEqualTo(2);
        assertThat(itemSentinel(WEB, "cntAll")).isEqualTo(3);
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
    void an_unsessioned_item_drops_out_of_the_excl_cancelled_tally_immediately() {
        apply(payment("pay1", T0));

        apply(new ProjectionItem(1L, SourceKind.PAYMENT, "payc", T0.plusSeconds(60), null,
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
        assertThat(itemSentinel(WEB, "cntExclCancelled")).isEqualTo(2);
        assertNoDrift();
    }

    @Test
    void a_second_distinct_cancel_of_the_same_item_changes_no_tally_and_converges_on_cancelledBy() {
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
        ProjectionSnapshot before = ProjectionSnapshot.of(dsl());

        apply(cancelOfUser(2L, "c1", "t1"));

        assertThat(pendingCancelStore.depth()).isEqualTo(1);
        assertThat(pendingCancelStore.oldestParkedAt()).isPresent();
        assertThat(cancelled(WEB, "t1")).isFalse();
        ProjectionSnapshot after = ProjectionSnapshot.of(dsl());
        // User 1's projection is untouched: rollups and page index are identical.
        assertThat(after.sessions()).isEqualTo(before.sessions());
        assertThat(after.plays()).isEqualTo(before.plays());
        assertThat(after.webHighLevel()).isEqualTo(before.webHighLevel());
        assertThat(after.ccHighLevel()).isEqualTo(before.ccHighLevel());
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

        apply(new ProjectionItem(1L, SourceKind.PAYMENT, "payc", T0.plusSeconds(60), null,
                null, null, null, TxTypes.CANCEL, new BigDecimal("-20.0000"), "EUR", null, "x1", null));

        assertThat(cancelledOf(CC, SourceKind.PAYMENT, "x1")).isTrue();
        assertThat(cancelledOf(CC, SourceKind.TRANSACTION, "x1")).isFalse();
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(rowVisible(CC, SourceKind.PAYMENT.code(), "x1")).isFalse();
        assertNoDrift();
    }

    @Test
    void a_parked_cancel_is_drained_only_by_an_item_of_its_own_source() {
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
        ProjectionItem cancel = new ProjectionItem(7L, SourceKind.PAYMENT, "pc1", T0, "P04", "keno", "b9",
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

    // --- R87: per-view visibility ----------------------------------------------------------------

    @Test
    void cancelling_the_only_web_item_hides_the_web_row_but_not_the_cc_row() {
        apply(tx("t1", "b1", "p1"), adjustment("a1", "b1", T0.plusSeconds(30), 11));

        apply(cancelOf("c1", "t1"));

        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isZero();
        assertThat(highLevelSentinel(CC, "cntExclCancelled")).isEqualTo(1);
        // The adjustment still survives in the session's rollup.
        assertThat(sessionField("txCountExclCancelled")).isEqualTo(1);
        assertNoDrift();
    }

    // --- R88: revival ----------------------------------------------------------------------------

    @Test
    void a_live_item_joining_an_emptied_session_revives_its_rows() {
        apply(tx("t1", "b1", "p1"));
        apply(cancelOf("c1", "t1"));
        assertThat(sessionVisible(WEB)).isFalse();

        apply(tx("t2", "b1", "p2"));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(highLevelSentinel(WEB, "cntExclCancelled")).isEqualTo(1);
        assertThat(highLevelSentinel(WEB, "cntAll")).isEqualTo(1);
        assertNoDrift();
    }

    @Test
    void a_cc_only_item_revives_only_the_cc_row() {
        apply(tx("t1", "b1", "p1"));
        apply(cancelOf("c1", "t1"));

        apply(adjustment("a1", "b1", T0.plusSeconds(30), 11));

        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isTrue();
        assertNoDrift();
    }

    @Test
    void a_live_item_joining_an_emptied_play_restores_the_session_play_count() {
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
        assertNoDrift();

        // Earlier by a day at a lower product: start AND product move, invisible WEB row moves
        // with its visibility, and the CC row is revived at the new key.
        apply(adjustment("a0", "b1", T0.minus(1, ChronoUnit.DAYS), 7));

        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(sessionStart()).isEqualTo(T0.minus(1, ChronoUnit.DAYS));
        assertNoDrift();

        // Later at a lower product still: product-only change, and the WEB row is revived.
        apply(txProduct("t9", "b1", "p9", T0.plusSeconds(500), 3));

        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(highLevelProduct(WEB)).isEqualTo("P03");
        assertNoDrift();

        // And cancelling within the re-anchored session uncounts at the session's NEW key.
        apply(cancelOf("c9", "t9"));

        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isTrue();
        assertNoDrift();
    }

    // --- R90: order independence -----------------------------------------------------------------

    @Test
    void every_order_of_a_cancel_its_original_and_a_cc_only_item_converges() {
        // t1 and t2 in different plays; a1 is /cc-only and a day earlier at a lower product, so
        // it re-anchors the session (start and product) whenever it arrives after the others.
        List<ProjectionItem> items = List.of(
                txProduct("t1", "b1", "p1", T0, 11),
                txProduct("t2", "b1", "p2", T0.plusSeconds(10), 11),
                cancelOf("c1", "t1"),
                adjustment("a1", "b1", T0.minus(1, ChronoUnit.DAYS), 7));

        ProjectionSnapshot expected = assertAllOrdersConverge(items);

        assertThat(expected.pendingCancels()).isEmpty();
        assertThat(sessionVisible(WEB)).isTrue();
        assertThat(sessionVisible(CC)).isTrue();
        assertThat(sessionField("playCountExclCancelled")).isEqualTo(1);
    }

    @Test
    void every_order_of_a_cancel_of_the_only_web_item_and_a_cc_only_item_converges() {
        List<ProjectionItem> items = List.of(
                txProduct("t1", "b1", "p1", T0, 11),
                cancelOf("c1", "t1"),
                adjustment("a1", "b1", T0.minus(1, ChronoUnit.DAYS), 7));

        assertAllOrdersConverge(items);

        assertThat(sessionVisible(WEB)).isFalse();
        assertThat(sessionVisible(CC)).isTrue();
    }

    /**
     * Applies every permutation into clean state, once one item per batch (cancel and original in
     * different transactions: the before case parks) and once as a single batch (parked and
     * drained within one transaction), and asserts every resulting snapshot is identical and
     * consistent. Leaves the last run's state in place.
     */
    private ProjectionSnapshot assertAllOrdersConverge(List<ProjectionItem> items) {
        ProjectionSnapshot expected = null;
        for (List<ProjectionItem> order : permutations(items)) {
            for (boolean singleBatch : List.of(false, true)) {
                clearProjection();
                if (singleBatch) {
                    writer.apply(new ProjectionBatch("s", order, ++offset));
                } else {
                    for (ProjectionItem item : order) {
                        apply(item);
                    }
                }
                ProjectionSnapshot snapshot = ProjectionSnapshot.of(dsl());
                String described = order.stream().map(ProjectionItem::sourceId).toList()
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

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
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

    /** Counters, rollups, group locators and the page index all agree with the item rows. */
    private void assertNoDrift() {
        assertThat(new ProjectionInvariants(dsl()).drift()).isEmpty();
    }

    // --- fixtures --------------------------------------------------------------------------------

    private void apply(ProjectionItem... items) {
        writer.apply(new ProjectionBatch("s", List.of(items), ++offset));
    }

    private List<ProjectionItem> drain(long userId, SourceKind source, String sourceId) {
        // drainFor joins the projection transaction (MANDATORY); supply one, as the writer does.
        return transactionTemplate.execute(status ->
                pendingCancelStore.drainFor(Set.of(new ItemRef(userId, source, sourceId))));
    }

    private ProjectionItem tx(String id, String batchId, String subBatchId) {
        return txAt(id, batchId, subBatchId, T0);
    }

    private ProjectionItem txAt(String id, String batchId, String subBatchId, Instant at) {
        return txProduct(id, batchId, subBatchId, at, 11);
    }

    private ProjectionItem txProduct(
            String id, String batchId, String subBatchId, Instant at, int product) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, at, Products.id(product), Products.type(product),
                batchId, subBatchId, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionItem txTyped(
            String id, String batchId, String subBatchId, short type, String amount) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, T0.plusSeconds(1), "P11", "instants", batchId, subBatchId,
                type, new BigDecimal(amount), "EUR", null, null, null);
    }

    private ProjectionItem cancelOf(String id, String cancels) {
        return cancelOfUser(1L, id, cancels);
    }

    private ProjectionItem cancelOfUser(long userId, String id, String cancels) {
        return new ProjectionItem(
                userId, SourceKind.TRANSACTION, id, T0.plusSeconds(120), "P11", "instants", null, null,
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, cancels, null);
    }

    private ProjectionItem adjustment(String id, String batchId, Instant at, int product) {
        return new ProjectionItem(
                1L, SourceKind.MANUAL_ADJUSTMENT, id, at, Products.id(product), Products.type(product),
                batchId, null, TxTypes.NEUTRAL, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionItem payment(String id, Instant at) {
        return new ProjectionItem(
                1L, SourceKind.PAYMENT, id, at, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("20.0000"), "EUR", null, null, null);
    }

    private static String itemTable(int view) {
        return view == WEB ? "wqWebItem" : "wqCcItem";
    }

    private static String pageTable(int view) {
        return view == WEB ? "wqWebHighLevel" : "wqCcHighLevel";
    }

    private boolean cancelled(int view, String sourceId) {
        return cancelledOf(view, SourceKind.TRANSACTION, sourceId);
    }

    private boolean cancelledOf(int view, SourceKind source, String sourceId) {
        return dsl().fetchOne(
                "select \"cancelled\" from \"" + itemTable(view)
                        + "\" where \"source\" = ? and \"sourceId\" = ?",
                (short) source.code(), sourceId)
                .get(0, Boolean.class);
    }

    private String cancelledBy(String sourceId) {
        return dsl().fetchOne(
                "select \"cancelledBy\" from \"wqCcItem\" where \"source\" = 1 and \"sourceId\" = ?",
                sourceId).get(0, String.class);
    }

    private boolean exists(int view, String sourceId) {
        return dsl().fetchOne("select count(*)::int from \"" + itemTable(view)
                + "\" where \"sourceId\" = ?", sourceId).get(0, Integer.class) > 0;
    }

    private int sessionField(String field) {
        return dsl().fetchOne("select \"" + field + "\" from \"wqSession\"").get(0, Integer.class);
    }

    private BigDecimal sessionMoney(String field) {
        return dsl().fetchOne("select \"" + field + "\" from \"wqSession\"")
                .get(0, BigDecimal.class);
    }

    private Instant sessionStart() {
        return dsl().fetchOne("select \"startedAt\" from \"wqSession\"").get(0, Instant.class);
    }

    private int playField(String subBatchId, String field) {
        return dsl().fetchOne("select \"" + field + "\" from \"wqPlay\" where \"subBatchId\" = ?",
                subBatchId).get(0, Integer.class);
    }

    private BigDecimal playMoney(String subBatchId, String field) {
        return dsl().fetchOne("select \"" + field + "\" from \"wqPlay\" where \"subBatchId\" = ?",
                subBatchId).get(0, BigDecimal.class);
    }

    private boolean sessionVisible(int view) {
        return dsl().fetchOne("select \"visibleExclCancelled\" from \"" + pageTable(view)
                + "\" where \"kind\" = 0").get(0, Boolean.class);
    }

    private String highLevelProduct(int view) {
        return dsl().fetchOne("select \"productId\" from \"" + pageTable(view)
                + "\" where \"kind\" = 0").get(0, String.class);
    }

    private boolean rowVisible(int view, int kind, String refId) {
        return dsl().fetchOne("select \"visibleExclCancelled\" from \"" + pageTable(view)
                + "\" where \"kind\" = ? and \"refId\" = ?", (short) kind, refId)
                .get(0, Boolean.class);
    }

    private int highLevelSentinel(int view, String column) {
        return sentinel(view, 1, column);
    }

    private int itemSentinel(int view, String column) {
        return sentinel(view, 0, column);
    }

    private int sentinel(int view, int pageUnit, String column) {
        return dsl().fetchOne("""
                select coalesce(sum("%s"),0)::int from "wqCountDay"
                where "viewKind" = ? and "pageUnit" = ? and "productType" = '' and "typeId" = 0
                """.formatted(column), (short) view, (short) pageUnit).get(0, Integer.class);
    }
}
