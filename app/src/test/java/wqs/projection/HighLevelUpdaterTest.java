package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import wqs.db.TestSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

class HighLevelUpdaterTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-08-03T09:00:00Z");
    /** Each view's page index: the "onWeb" timeline rows with /web's visibility, or every row. */
    private static final String WEB = ProjectionInvariants.WEB_TIMELINE + " web";
    private static final String CC = ProjectionInvariants.CC_TIMELINE + " cc";

    @BeforeEach
    void setUp() {
        // July and August 2034: the month-boundary move needs both partitions.
        new PartitionManager(jdbc()).ensurePartitionsAhead(YearMonth.of(2034, 7), 1);
        clean();
    }

    @Test
    void a_new_session_inserts_one_row_per_view_it_feeds() {
        HighLevelResult result = apply(tx("t1", "b1", T0), newSession(T0, 11));

        assertThat(result.insertedCount()).isEqualTo(2);
        assertThat(count(WEB)).isEqualTo(1);
        assertThat(count(CC)).isEqualTo(1);
    }

    @Test
    void a_transaction_joining_an_existing_session_inserts_nothing() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));

        HighLevelResult result = apply(tx("t2", "b1", T0.plusSeconds(10)), joined(T0, 11));

        assertThat(result.insertedCount()).isZero();
        assertThat(count(WEB)).isEqualTo(1);
        assertThat(count(CC)).isEqualTo(1);
    }

    @Test
    void an_unsessioned_transaction_is_always_its_own_high_level_row() {
        HighLevelResult result = apply(payment("pay1", T0), RollupResult.NONE);

        // Payments feed /cc only.
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(count(CC)).isEqualTo(1);
        assertThat(count(WEB)).isZero();
        TestSql.Row row = db().row("select * from " + CC);
        assertThat(row.get("kind", Short.class)).isEqualTo((short) 4);
        assertThat(row.get("refId", String.class)).isEqualTo("pay1");
    }

    @Test
    void a_session_row_is_keyed_by_batchId_and_an_transaction_row_by_sourceId() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        ProjectionTransaction prize = new ProjectionTransaction(
                1L, SourceKind.CLAIMABLE_PRIZE, "prize1", T0.plusSeconds(5), "P11", "instants", null, null,
                TxTypes.WIN, new BigDecimal("7.0000"), "EUR", null, null, null);
        apply(prize, RollupResult.NONE);

        assertThat(refIds(WEB)).containsExactlyInAnyOrder("b1", "prize1");
    }

    @Test
    void new_rows_are_visible_in_the_excl_cancelled_view() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));

        assertThat(visible(WEB)).isTrue();
    }

    @Test
    void a_session_row_is_keyed_on_the_session_start_not_the_triggering_transaction() {
        // The first transaction to reach the updater is not the earliest: the session starts at T0.
        apply(tx("t1", "b1", T0.plusSeconds(60)), newSession(T0, 11));

        assertThat(occurredAts(WEB)).containsExactly(T0);
    }

    @Test
    void a_cancellation_creates_no_row() {
        ProjectionTransaction sessionedCancel = new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "t2", T0, "P11", "instants", "b1", "p1",
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "t1", null);
        ProjectionTransaction unsessionedCancel = new ProjectionTransaction(
                1L, SourceKind.PAYMENT, "pay2", T0, null, null, null, null,
                TxTypes.CANCEL, new BigDecimal("-50.0000"), "EUR", null, "pay1", null);

        assertThat(apply(sessionedCancel, RollupResult.NONE)).isEqualTo(HighLevelResult.NONE);
        assertThat(apply(unsessionedCancel, RollupResult.NONE)).isEqualTo(HighLevelResult.NONE);
        assertThat(count(WEB)).isZero();
        assertThat(count(CC)).isZero();
    }

    @Test
    void an_earlier_start_moves_the_row_within_a_month() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        Instant earlier = T0.minusSeconds(3600);

        HighLevelResult result = apply(tx("t0", "b1", earlier), moved(earlier, 11, T0, 11));

        assertThat(result.insertedCount()).isZero();
        assertThat(result.moved()).containsExactlyInAnyOrder(ViewKind.WEB, ViewKind.CC);
        assertThat(result.movedFrom()).isEqualTo(T0);
        assertThat(result.movedFromProductType()).isEqualTo(Products.type(11));
        assertThat(result.movedVisible())
                .containsExactlyInAnyOrderEntriesOf(Map.of(ViewKind.WEB, true, ViewKind.CC, true));
        assertThat(occurredAts(WEB)).containsExactly(earlier);
        assertThat(occurredAts(CC)).containsExactly(earlier);
    }

    @Test
    void an_earlier_start_moves_the_row_across_a_month_boundary() {
        Instant augStart = Instant.parse("2034-08-01T00:10:00Z");
        Instant julyStart = Instant.parse("2034-07-31T23:50:00Z");
        apply(tx("t1", "b1", augStart), newSession(augStart, 11));

        apply(tx("t0", "b1", julyStart), moved(julyStart, 11, augStart, 11));

        assertThat(occurredAts(WEB)).containsExactly(julyStart);
        assertThat(count(WEB)).isEqualTo(1);
        assertThat(count(CC)).isEqualTo(1);
    }

    @Test
    void a_move_preserves_visibility_and_the_move_also_updates_the_product() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        db().execute("update \"wqPrjTimeline\" set \"webVisibleExclCancelled\" = false");
        Instant earlier = T0.minusSeconds(60);

        HighLevelResult result = apply(tx("t0", "b1", earlier), moved(earlier, 7, T0, 11));

        // The counter move needs each moved row's visibility (it decides cntExclCancelled).
        assertThat(result.movedVisible())
                .containsExactlyInAnyOrderEntriesOf(Map.of(ViewKind.WEB, false, ViewKind.CC, true));
        // The row moved hidden, then t0 -- a live transaction feeding /web -- revived it at the new key
        // (spec 6.3); the counter move uses the moved visibility, the revival adds excl after.
        assertThat(result.revived()).containsExactly(ViewKind.WEB);
        assertThat(visible(WEB)).isTrue();
        assertThat(visible(CC)).isTrue();
        assertThat(db().value("select \"productId\" from " + WEB, String.class)).isEqualTo(Products.id(7));
        assertThat(db().value("select \"productType\" from " + WEB, String.class)).isEqualTo(Products.type(7));
    }

    @Test
    void a_live_transaction_revives_hidden_session_rows_only_in_the_views_its_source_feeds() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        db().execute("update \"wqPrjTimeline\" set \"webVisibleExclCancelled\" = false");
        db().execute("update \"wqPrjTimeline\" set \"visibleExclCancelled\" = false");

        HighLevelResult result = apply(ccOnly("a1", "b1", T0.plusSeconds(5)), joined(T0, 11));

        assertThat(result.inserted()).isEmpty();
        assertThat(result.revived()).containsExactly(ViewKind.CC);
        assertThat(visible(CC)).isTrue();
        assertThat(visible(WEB)).isFalse();

        // Already visible: nothing to revive.
        assertThat(apply(tx("t2", "b1", T0.plusSeconds(6)), joined(T0, 11)).revived())
                .containsExactly(ViewKind.WEB);
        assertThat(apply(tx("t3", "b1", T0.plusSeconds(7)), joined(T0, 11)).revived()).isEmpty();
    }

    @Test
    void a_product_only_change_reports_each_moved_rows_visibility() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        db().execute("update \"wqPrjTimeline\" set \"visibleExclCancelled\" = false");

        HighLevelResult result = apply(tx("t2", "b1", T0.plusSeconds(5)), moved(T0, 7, T0, 11));

        assertThat(result.movedVisible())
                .containsExactlyInAnyOrderEntriesOf(Map.of(ViewKind.WEB, true, ViewKind.CC, false));
    }

    @Test
    void movedVisible_is_empty_when_nothing_moved() {
        HighLevelResult result = apply(tx("t1", "b1", T0), newSession(T0, 11));

        assertThat(result.movedVisible()).isEmpty();
    }

    @Test
    void a_product_only_change_updates_the_row_in_place() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));

        HighLevelResult result = apply(tx("t2", "b1", T0.plusSeconds(5)), moved(T0, 7, T0, 11));

        assertThat(result.insertedCount()).isZero();
        assertThat(result.moved()).containsExactlyInAnyOrder(ViewKind.WEB, ViewKind.CC);
        assertThat(result.movedFrom()).isEqualTo(T0);
        assertThat(result.movedFromProductType()).isEqualTo(Products.type(11));
        assertThat(occurredAts(WEB)).containsExactly(T0);
        assertThat(db().value("select \"productId\" from " + WEB, String.class)).isEqualTo(Products.id(7));
        assertThat(db().value("select \"productType\" from " + WEB, String.class)).isEqualTo(Products.type(7));
        assertThat(db().value("select \"productId\" from " + CC, String.class)).isEqualTo(Products.id(7));
        assertThat(db().value("select \"productType\" from " + CC, String.class)).isEqualTo(Products.type(7));
    }

    @Test
    void a_move_materialises_a_missing_row_in_the_views_the_transaction_feeds() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        // Take the row off /web: as if no /web-source transaction had joined yet.
        db().execute("update \"wqPrjTimeline\" set \"onWeb\" = false, \"webVisibleExclCancelled\" = false");
        Instant earlier = T0.minusSeconds(60);

        HighLevelResult result = apply(tx("t0", "b1", earlier), moved(earlier, 11, T0, 11));

        // /cc row moved (0); the missing /web row is created at the current key.
        assertThat(result.inserted()).containsExactly(ViewKind.WEB);
        assertThat(result.moved()).containsExactly(ViewKind.CC);
        assertThat(result.movedVisible()).containsOnlyKeys(ViewKind.CC);
        assertThat(occurredAts(WEB)).containsExactly(earlier);
        assertThat(occurredAts(CC)).containsExactly(earlier);
    }

    @Test
    void the_end_state_does_not_depend_on_which_source_opens_the_session() {
        ProjectionTransaction cc = ccOnly("m1", "b1", T0.plusSeconds(30));
        ProjectionTransaction web = tx("t1", "b1", T0);

        List<HighLevelResult> first = driveBoth(cc, web);
        List<String> ccFirst = snapshot();
        clean();
        List<HighLevelResult> second = driveBoth(web, cc);

        assertThat(snapshot()).isEqualTo(ccFirst);
        // Per-view insert totals (what drives per-view counters) are order independent too.
        assertThat(insertedViews(first)).containsExactlyInAnyOrderElementsOf(insertedViews(second));
        assertThat(insertedViews(first)).hasSize(2);
        // cc-first: the earlier TRANSACTION re-anchors the session, moving only the /cc row.
        assertThat(first.get(1).moved()).containsExactly(ViewKind.CC);
        assertThat(first.get(1).movedFrom()).isEqualTo(T0.plusSeconds(30));
        assertThat(first.get(1).inserted()).containsExactly(ViewKind.WEB);
        assertThat(second.stream().flatMap(r -> r.moved().stream())).isEmpty();
        assertThat(count(WEB)).isEqualTo(1);
        assertThat(count(CC)).isEqualTo(1);
    }

    @Test
    void a_sessioned_transaction_without_a_session_key_is_a_contract_violation() {
        assertThatThrownBy(() -> apply(tx("t1", "b1", T0), RollupResult.NONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void there_is_exactly_one_session_row_per_view_after_repeated_moves() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        Instant e1 = T0.minusSeconds(100);
        Instant e2 = T0.minusSeconds(200);
        apply(tx("t2", "b1", e1), moved(e1, 11, T0, 11));
        apply(tx("t3", "b1", e2), moved(e2, 11, e1, 11));

        assertThat(count(WEB)).isEqualTo(1);
        assertThat(count(CC)).isEqualTo(1);
        assertThat(occurredAts(WEB)).containsExactly(e2);
    }

    /** Drives the real RollupUpdater then HighLevelUpdater, one transaction per projected transaction. */
    private List<HighLevelResult> driveBoth(ProjectionTransaction... transactions) {
        List<HighLevelResult> results = new java.util.ArrayList<>();
        for (ProjectionTransaction transaction : transactions) {
            results.add(inTx().execute(status -> {
                RollupResult rollup = new RollupUpdater(jdbc()).applyTransaction(transaction);
                return new HighLevelUpdater(jdbc()).applyTransaction(transaction, rollup);
            }));
        }
        return results;
    }

    private static List<ViewKind> insertedViews(List<HighLevelResult> results) {
        return results.stream().flatMap(r -> r.inserted().stream()).toList();
    }

    private void clean() {
        db().execute("delete from \"wqPrjTimeline\"");
        db().execute("delete from \"wqPrjGroupLocator\"");
        db().execute("delete from \"wqPrjPlay\"");
        db().execute("delete from \"wqPrjSession\"");
    }

    private List<String> snapshot() {
        return java.util.stream.Stream.of(WEB, CC)
                .flatMap(t -> db().maps("select \"userId\",\"occurredAt\",\"kind\",\"refId\","
                        + "\"productId\",\"productType\",\"currency\",\"visibleExclCancelled\" from " + t
                        + " order by 1,2,3,4").stream()
                        .map(r -> t + r.values()))
                .toList();
    }

    private ProjectionTransaction ccOnly(String id, String batchId, Instant at) {
        return new ProjectionTransaction(
                1L, SourceKind.MANUAL_ADJUSTMENT, id, at, "P11", "instants", batchId, null,
                TxTypes.NEUTRAL, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private HighLevelResult apply(ProjectionTransaction transaction, RollupResult rollup) {
        return inTx().execute(status -> new HighLevelUpdater(jdbc()).applyTransaction(transaction, rollup));
    }

    private static RollupResult newSession(Instant start, int product) {
        return new RollupResult(true, true, start, Products.id(product), Products.type(product),
                "EUR", null, null, false);
    }

    private static RollupResult joined(Instant start, int product) {
        return new RollupResult(false, false, start, Products.id(product), Products.type(product),
                "EUR", null, null, false);
    }

    private static RollupResult moved(Instant start, int product,
                                      Instant prevStart, int prevProduct) {
        return new RollupResult(false, false, start, Products.id(product), Products.type(product),
                "EUR", prevStart, Products.type(prevProduct), true);
    }

    private ProjectionTransaction tx(String id, String batchId, Instant at) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, at, "P11", "instants", batchId, batchId + "-p",
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction payment(String id, Instant at) {
        return new ProjectionTransaction(
                1L, SourceKind.PAYMENT, id, at, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("50.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return db().value("select count(*)::int from " + table, Integer.class);
    }

    private List<String> refIds(String table) {
        return db().column("select \"refId\" from " + table, String.class);
    }

    private List<Instant> occurredAts(String table) {
        return db().column("select \"occurredAt\" from " + table, java.time.OffsetDateTime.class).stream()
                .map(o -> o.withOffsetSameInstant(ZoneOffset.UTC).toInstant()).toList();
    }

    private Boolean visible(String table) {
        return db().value("select \"visibleExclCancelled\" from " + table, Boolean.class);
    }
}
