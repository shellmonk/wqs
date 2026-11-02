package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;

class HighLevelUpdaterTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-08-03T09:00:00Z");

    @BeforeEach
    void setUp() {
        // July and August 2034: the month-boundary move needs both partitions.
        new PartitionManager(dsl()).ensurePartitionsAhead(YearMonth.of(2034, 7), 1);
        dsl().execute("delete from \"wqWebHighLevel\"");
        dsl().execute("delete from \"wqCcHighLevel\"");
    }

    @Test
    void a_new_session_inserts_one_row_per_view_it_feeds() {
        int inserted = apply(tx("t1", "b1", T0), newSession(T0, 11));

        assertThat(inserted).isEqualTo(2);
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
    }

    @Test
    void a_transaction_joining_an_existing_session_inserts_nothing() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));

        int inserted = apply(tx("t2", "b1", T0.plusSeconds(10)), joined(T0, 11));

        assertThat(inserted).isZero();
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
    }

    @Test
    void an_unsessioned_item_is_always_its_own_high_level_row() {
        int inserted = apply(payment("pay1", T0), RollupResult.NONE);

        // Payments feed /cc only.
        assertThat(inserted).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
        assertThat(count("wqWebHighLevel")).isZero();
        Record row = dsl().fetchOne("select * from \"wqCcHighLevel\"");
        assertThat(row.get("kind", Short.class)).isEqualTo((short) 4);
        assertThat(row.get("refId", String.class)).isEqualTo("pay1");
    }

    @Test
    void a_session_row_is_keyed_by_batchId_and_an_item_row_by_sourceId() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        ProjectionItem prize = new ProjectionItem(
                1L, SourceKind.CLAIMABLE_PRIZE, "prize1", T0.plusSeconds(5), 11, null, null,
                (short) 3, new BigDecimal("7.0000"), "EUR", null, null, null);
        apply(prize, RollupResult.NONE);

        assertThat(refIds("wqWebHighLevel")).containsExactlyInAnyOrder("b1", "prize1");
    }

    @Test
    void new_rows_are_visible_in_the_excl_cancelled_view() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));

        assertThat(visible("wqWebHighLevel")).isTrue();
    }

    @Test
    void a_session_row_is_keyed_on_the_session_start_not_the_triggering_item() {
        // The first item to reach the updater is not the earliest: the session starts at T0.
        apply(tx("t1", "b1", T0.plusSeconds(60)), newSession(T0, 11));

        assertThat(occurredAts("wqWebHighLevel")).containsExactly(T0);
    }

    @Test
    void a_cancellation_creates_no_row() {
        ProjectionItem sessionedCancel = new ProjectionItem(
                1L, SourceKind.TRANSACTION, "t2", T0, 11, "b1", "p1",
                (short) 2, new BigDecimal("-5.0000"), "EUR", null, "t1", null);
        ProjectionItem unsessionedCancel = new ProjectionItem(
                1L, SourceKind.PAYMENT, "pay2", T0, null, null, null,
                (short) 9, new BigDecimal("-50.0000"), "EUR", null, "pay1", null);

        assertThat(apply(sessionedCancel, RollupResult.NONE)).isZero();
        assertThat(apply(unsessionedCancel, RollupResult.NONE)).isZero();
        assertThat(count("wqWebHighLevel")).isZero();
        assertThat(count("wqCcHighLevel")).isZero();
    }

    @Test
    void an_earlier_start_moves_the_row_within_a_month() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        Instant earlier = T0.minusSeconds(3600);

        int inserted = apply(tx("t0", "b1", earlier), moved(earlier, 11, T0, 11));

        assertThat(inserted).isZero();
        assertThat(occurredAts("wqWebHighLevel")).containsExactly(earlier);
        assertThat(occurredAts("wqCcHighLevel")).containsExactly(earlier);
    }

    @Test
    void an_earlier_start_moves_the_row_across_a_month_boundary() {
        Instant augStart = Instant.parse("2034-08-01T00:10:00Z");
        Instant julyStart = Instant.parse("2034-07-31T23:50:00Z");
        apply(tx("t1", "b1", augStart), newSession(augStart, 11));

        apply(tx("t0", "b1", julyStart), moved(julyStart, 11, augStart, 11));

        assertThat(occurredAts("wqWebHighLevel")).containsExactly(julyStart);
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
    }

    @Test
    void a_move_preserves_visibility_and_the_move_also_updates_the_product() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        dsl().execute("update \"wqWebHighLevel\" set \"visibleExclCancelled\" = false");
        Instant earlier = T0.minusSeconds(60);

        apply(tx("t0", "b1", earlier), moved(earlier, 7, T0, 11));

        assertThat(visible("wqWebHighLevel")).isFalse();
        assertThat(visible("wqCcHighLevel")).isTrue();
        assertThat(dsl().fetchOne("select \"productId\" from \"wqWebHighLevel\"")
                .get(0, Integer.class)).isEqualTo(7);
    }

    @Test
    void a_product_only_change_updates_the_row_in_place() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));

        int inserted = apply(tx("t2", "b1", T0.plusSeconds(5)), moved(T0, 7, T0, 11));

        assertThat(inserted).isZero();
        assertThat(occurredAts("wqWebHighLevel")).containsExactly(T0);
        assertThat(dsl().fetchOne("select \"productId\" from \"wqWebHighLevel\"")
                .get(0, Integer.class)).isEqualTo(7);
        assertThat(dsl().fetchOne("select \"productId\" from \"wqCcHighLevel\"")
                .get(0, Integer.class)).isEqualTo(7);
    }

    @Test
    void a_move_only_touches_views_where_the_session_row_exists() {
        // A session whose row exists in /cc only (e.g. created by a /cc-only source).
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        dsl().execute("delete from \"wqWebHighLevel\"");
        Instant earlier = T0.minusSeconds(60);

        apply(tx("t0", "b1", earlier), moved(earlier, 11, T0, 11));

        assertThat(count("wqWebHighLevel")).isZero();
        assertThat(occurredAts("wqCcHighLevel")).containsExactly(earlier);
    }

    @Test
    void there_is_exactly_one_session_row_per_view_after_repeated_moves() {
        apply(tx("t1", "b1", T0), newSession(T0, 11));
        Instant e1 = T0.minusSeconds(100);
        Instant e2 = T0.minusSeconds(200);
        apply(tx("t2", "b1", e1), moved(e1, 11, T0, 11));
        apply(tx("t3", "b1", e2), moved(e2, 11, e1, 11));

        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
        assertThat(occurredAts("wqWebHighLevel")).containsExactly(e2);
    }

    @Test
    void it_requires_a_caller_transaction() {
        HighLevelUpdater updater = new HighLevelUpdater(dsl());
        // Direct construction has no proxy, so MANDATORY is enforced by Spring only; here we
        // assert the annotation is present rather than re-implementing the proxy.
        assertThat(HighLevelUpdater.class.getMethods())
                .filteredOn(m -> m.getName().equals("applyItem"))
                .allSatisfy(m -> assertThat(m.getAnnotation(
                        org.springframework.transaction.annotation.Transactional.class).propagation())
                        .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY));
        assertThat(updater).isNotNull();
    }

    private int apply(ProjectionItem item, RollupResult rollup) {
        return dsl().transactionResult(
                cfg -> new HighLevelUpdater(DSL.using(cfg)).applyItem(item, rollup));
    }

    private static RollupResult newSession(Instant start, Integer productId) {
        return new RollupResult(true, true, start, productId, null, null, false);
    }

    private static RollupResult joined(Instant start, Integer productId) {
        return new RollupResult(false, false, start, productId, null, null, false);
    }

    private static RollupResult moved(Instant start, Integer productId,
                                      Instant prevStart, Integer prevProductId) {
        return new RollupResult(false, false, start, productId, prevStart, prevProductId, true);
    }

    private ProjectionItem tx(String id, String batchId, Instant at) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, at, 11, batchId, batchId + "-p",
                (short) 1, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionItem payment(String id, Instant at) {
        return new ProjectionItem(
                1L, SourceKind.PAYMENT, id, at, null, null, null,
                (short) 9, new BigDecimal("50.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }

    private List<String> refIds(String table) {
        return dsl().fetch("select \"refId\" from \"" + table + "\"").getValues(0, String.class);
    }

    private List<Instant> occurredAts(String table) {
        return dsl().fetch("select \"occurredAt\" from \"" + table + "\"")
                .getValues(0, java.time.OffsetDateTime.class).stream()
                .map(o -> o.withOffsetSameInstant(ZoneOffset.UTC).toInstant()).toList();
    }

    private Boolean visible(String table) {
        return dsl().fetchOne("select \"visibleExclCancelled\" from \"" + table + "\"")
                .get(0, Boolean.class);
    }
}
