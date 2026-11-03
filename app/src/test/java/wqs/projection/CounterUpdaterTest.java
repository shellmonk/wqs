package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.CounterSentinels;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;

class CounterUpdaterTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-10-17T14:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2034-10-18T01:00:00Z");
    private static final Instant NEXT_MONTH = Instant.parse("2034-11-02T01:00:00Z");

    @BeforeEach
    void setUp() {
        new PartitionManager(jdbc()).ensurePartitionsAhead(YearMonth.of(2034, 10), 1);
        db().execute("delete from \"wqPrjCountDay\"");
        db().execute("delete from \"wqPrjCountMonth\"");
    }

    private void run(java.util.function.Consumer<CounterUpdater> action) {
        inTx().executeWithoutResult(status -> action.accept(new CounterUpdater(jdbc())));
    }

    @Test
    void an_transaction_counts_against_its_product_type_and_the_all_product_types_sentinel() {
        run(u -> u.countTransaction(tx("t1", "t11")));

        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "t11", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isEqualTo(1);
    }

    @Test
    void an_transaction_counts_in_every_view_its_source_feeds() {
        run(u -> u.countTransaction(tx("t1", "t11")));

        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.CC, PageUnit.TRANSACTION, "", T0)).isEqualTo(1);
    }

    @Test
    void a_payment_counts_only_in_cc_and_only_against_the_sentinel() {
        ProjectionTransaction payment = new ProjectionTransaction(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("50.0000"), "EUR", null, null, null);

        run(u -> u.countTransaction(payment));

        assertThat(dayCount(ViewKind.CC, PageUnit.TRANSACTION, "", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isZero();
        // No product type: (ALL, type) and (ALL, ALL) only.
        assertThat(rows("wqPrjCountDay")).isEqualTo(2);
    }

    @Test
    void day_and_month_tiers_both_advance() {
        run(u -> {
            u.countTransaction(tx("t1", "t11"));
            u.countTransaction(tx("t2", "t11"));
        });

        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isEqualTo(2);
        assertThat(monthCount(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isEqualTo(2);
    }

    @Test
    void high_level_counts_are_tracked_separately_from_transaction_counts() {
        run(u -> {
            u.countTransaction(tx("t1", "t11"));
            u.countHighLevel(1L, ViewKind.WEB, "t11", T0);
        });

        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
    }

    @Test
    void high_level_counting_is_per_view() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.CC, PageUnit.HIGH_LEVEL, "", T0)).isZero();
    }

    @Test
    void uncounting_reduces_only_the_excl_cancelled_tally() {
        run(u -> u.countTransaction(tx("t1", "t11")));

        run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.TRANSACTION, "t11", TxTypes.PLAY, T0));

        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "t11", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.TRANSACTION, "t11", T0)).isZero();
        assertThat(monthExcl(ViewKind.WEB, PageUnit.TRANSACTION, "", T0)).isZero();
    }

    @Test
    void a_move_deletes_the_counter_rows_it_empties_so_state_is_path_independent() {
        // Counted directly at its final key...
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t7", NEXT_DAY));
        List<String> direct = allCounterRows();
        db().execute("delete from \"wqPrjCountDay\"");
        db().execute("delete from \"wqPrjCountMonth\"");

        // ...or counted elsewhere first and moved there (spec P-1: identical state either way).
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));
        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t7", NEXT_DAY, true));

        assertThat(allCounterRows()).isEqualTo(direct);
        assertThat(db().value("select count(*)::int from \"wqPrjCountDay\" where \"cntAll\" = 0", Integer.class)).isZero();
    }

    @Test
    void uncounting_keeps_a_row_whose_cntAll_is_still_positive() {
        run(u -> u.countTransaction(tx("t1", "t11")));

        run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.TRANSACTION, "t11", TxTypes.PLAY, T0));

        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "t11", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.TRANSACTION, "t11", T0)).isZero();
    }

    @Test
    void a_move_across_days_shifts_day_rows_but_not_the_month_row() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t11", NEXT_DAY, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", NEXT_DAY)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", NEXT_DAY)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", NEXT_DAY)).isEqualTo(1);
    }

    @Test
    void a_move_across_months_shifts_month_rows_too() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t11", NEXT_MONTH, true));

        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", NEXT_MONTH)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", NEXT_MONTH)).isEqualTo(1);
    }

    @Test
    void a_product_only_move_shifts_the_own_product_row_and_leaves_the_sentinel() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t12", T0, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t12", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
    }

    @Test
    void an_invisible_row_moves_only_cntAll() {
        run(u -> {
            u.countHighLevel(1L, ViewKind.WEB, "t11", T0);
            u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", CounterSentinels.ALL_TYPES, T0);
        });

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t11", NEXT_DAY, false));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", NEXT_DAY)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", NEXT_DAY)).isZero();
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", NEXT_DAY)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isZero();
    }

    @Test
    void a_move_to_the_same_day_and_product_changes_nothing() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t11", T0.plusSeconds(60), true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
    }

    @Test
    void a_null_product_before_a_move_counts_the_sentinel_only_and_gains_the_new_product() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, null, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, null, T0, "t11", T0, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(monthExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
    }

    @Test
    void a_null_product_after_a_move_drops_the_old_product_and_keeps_the_sentinel() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, null, NEXT_DAY, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", NEXT_DAY)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        // Emptied rows are deleted (sparse): only 0@NEXT_DAY remains in the day tier, and the
        // month tier keeps only the sentinel.
        assertThat(rows("wqPrjCountDay")).isEqualTo(1);
        assertThat(rows("wqPrjCountMonth")).isEqualTo(1);
    }

    @Test
    void decrementing_a_missing_row_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                run(u -> u.moveHighLevel(1L, ViewKind.WEB, "t11", T0, "t11", NEXT_DAY, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wqPrjCountDay")
                .hasMessageContaining("productType='t11'")
                .hasMessageContaining("type=''");
    }

    @Test
    void uncounting_twice_throws_instead_of_clamping() {
        run(u -> u.countTransaction(tx("t1", "t11")));
        run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.TRANSACTION, "t11", TxTypes.PLAY, T0));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.TRANSACTION, "t11", TxTypes.PLAY, T0)))
                .isInstanceOf(RuntimeException.class);
        assertThat(dayCount(ViewKind.WEB, PageUnit.TRANSACTION, "t11", T0)).isEqualTo(1);
    }

    @Test
    void recounting_restores_only_the_excl_cancelled_tally_and_is_strict() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "t11", T0));
        run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", CounterSentinels.ALL_TYPES, T0));

        run(u -> u.recountExclCancelled(1L, ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", CounterSentinels.ALL_TYPES, T0));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", T0)).isEqualTo(1);
        assertThat(monthExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, "", T0)).isEqualTo(1);
        // Excl above cntAll: the CHECK rejects it.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                run(u -> u.recountExclCancelled(1L, ViewKind.WEB, PageUnit.HIGH_LEVEL, "t11", CounterSentinels.ALL_TYPES, T0)))
                .isInstanceOf(RuntimeException.class);
        // No row to restore: never created.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                run(u -> u.recountExclCancelled(1L, ViewKind.CC, PageUnit.HIGH_LEVEL, "t11", CounterSentinels.ALL_TYPES, T0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1");
        assertThat(rows("wqPrjCountDay")).isEqualTo(2);
    }

    @Test
    void the_check_constraint_rejects_negative_and_excl_above_all() {
        run(u -> u.countTransaction(tx("t1", "t11")));

        for (String table : new String[] {"wqPrjCountDay", "wqPrjCountMonth"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db().execute(
                    "update \"" + table + "\" set \"cntAll\" = -1, \"cntExclCancelled\" = -1"))
                    .hasMessageContaining("check");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db().execute(
                    "update \"" + table + "\" set \"cntExclCancelled\" = \"cntAll\" + 1"))
                    .hasMessageContaining("check");
        }
    }

    @Test
    void transaction_fans_out_to_four_keys() {
        run(u -> u.countTransaction(tx("t1", "instants")));

        assertThat(dayKeys(ViewKind.WEB, PageUnit.TRANSACTION)).containsExactlyInAnyOrder(
                "instants/" + TxTypes.PLAY, "instants/", "/" + TxTypes.PLAY, "/");
        assertThat(monthKeys(ViewKind.WEB, PageUnit.TRANSACTION)).containsExactlyInAnyOrder(
                "instants/" + TxTypes.PLAY, "instants/", "/" + TxTypes.PLAY, "/");
    }

    @Test
    void null_product_type_fans_out_to_two_keys() {
        run(u -> u.countTransaction(txOfType("t1", null, TxTypes.WIN)));

        assertThat(dayKeys(ViewKind.WEB, PageUnit.TRANSACTION))
                .containsExactlyInAnyOrder("/" + TxTypes.WIN, "/");
        assertThat(monthKeys(ViewKind.WEB, PageUnit.TRANSACTION))
                .containsExactlyInAnyOrder("/" + TxTypes.WIN, "/");
    }

    @Test
    void high_level_row_uses_type_sentinel_only() {
        run(u -> {
            u.countHighLevel(1L, ViewKind.CC, "instants", T0);
            u.countHighLevel(1L, ViewKind.CC, null, T0);
        });

        assertThat(dayKeys(ViewKind.CC, PageUnit.HIGH_LEVEL))
                .containsExactlyInAnyOrder("instants/", "/");
        assertThat(monthKeys(ViewKind.CC, PageUnit.HIGH_LEVEL))
                .containsExactlyInAnyOrder("instants/", "/");
    }

    @Test
    void cancel_moves_excl_counts_on_all_four_keys() {
        run(u -> u.countTransaction(tx("t1", "instants")));

        run(u -> u.uncountExclCancelled(
                1L, ViewKind.WEB, PageUnit.TRANSACTION, "instants", TxTypes.PLAY, T0));

        for (String table : new String[] {"wqPrjCountDay", "wqPrjCountMonth"}) {
            var rows = db().rows("select \"cntAll\", \"cntExclCancelled\" from \"" + table
                    + "\" where \"viewKind\" = 1 and \"pageUnit\" = 0");
            assertThat(rows).hasSize(4);
            assertThat(rows).extracting(r -> r.get("cntAll", Long.class)).containsOnly(1L);
            assertThat(rows).extracting(r -> r.get("cntExclCancelled", Long.class)).containsOnly(0L);
        }

        run(u -> u.recountExclCancelled(
                1L, ViewKind.WEB, PageUnit.TRANSACTION, "instants", TxTypes.PLAY, T0));
        assertThat(db().maps("select 1 from \"wqPrjCountDay\" where \"cntExclCancelled\" = 0"))
                .isEmpty();
    }

    @Test
    void high_level_cancel_moves_excl_on_both_type_sentinel_keys() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, "instants", T0));

        run(u -> u.uncountExclCancelled(
                1L, ViewKind.WEB, PageUnit.HIGH_LEVEL, "instants", CounterSentinels.ALL_TYPES, T0));

        assertThat(db().maps("select 1 from \"wqPrjCountDay\" where \"cntExclCancelled\" = 0"))
                .hasSize(2);
    }

    private List<String> dayKeys(ViewKind v, PageUnit u) {
        return keys("wqPrjCountDay", v, u);
    }

    private List<String> monthKeys(ViewKind v, PageUnit u) {
        return keys("wqPrjCountMonth", v, u);
    }

    private List<String> keys(String table, ViewKind v, PageUnit u) {
        return db().column("select \"productType\" || '/' || \"type\" from \"" + table
                + "\" where \"viewKind\" = ? and \"pageUnit\" = ?", String.class, (short) v.code(), u.code());
    }

    private ProjectionTransaction txOfType(String id, String productType, String type) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, T0, "P1", productType, "b1", "p1",
                type, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction tx(String id, String productType) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, id, T0, "P1", productType, "b1", "p1",
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private int dayCount(ViewKind v, PageUnit u, String product, Instant at) {
        return counter("wqPrjCountDay", "cntAll", v, u, product, at, false);
    }

    private int dayExcl(ViewKind v, PageUnit u, String product, Instant at) {
        return counter("wqPrjCountDay", "cntExclCancelled", v, u, product, at, false);
    }

    private int monthCount(ViewKind v, PageUnit u, String product, Instant at) {
        return counter("wqPrjCountMonth", "cntAll", v, u, product, at, true);
    }

    private int monthExcl(ViewKind v, PageUnit u, String product, Instant at) {
        return counter("wqPrjCountMonth", "cntExclCancelled", v, u, product, at, true);
    }

    private List<String> allCounterRows() {
        return java.util.stream.Stream.of("wqPrjCountDay", "wqPrjCountMonth")
                .flatMap(t -> db().maps("select * from \"" + t + "\" order by 1, 2, 3, 4, 5, 6")
                        .stream().map(r -> t + r.values()))
                .toList();
    }

    private int rows(String table) {
        return db().value("select count(*)::int from \"" + table + "\"", Integer.class);
    }

    /**
     * Test-side verification only: reads one counter at (product type, ALL types), 0 when the
     * sparse row does not exist. {@code ""} is the ALL-product-types sentinel.
     */
    private int counter(String table, String column, ViewKind view, PageUnit unit, String product,
                        Instant at, boolean month) {
        java.time.LocalDate day = java.time.LocalDate.ofInstant(at, java.time.ZoneOffset.UTC);
        java.time.LocalDate period = month ? day.withDayOfMonth(1) : day;
        return db().value("""
                select coalesce(sum("%s"), 0)::int from "%s"
                where "userId" = 1 and "viewKind" = ? and "pageUnit" = ? and "productType" = ?
                  and "type" = '' and "period" = ?
                """.formatted(column, table), Integer.class, (short) view.code(), unit.code(), product, period);
    }
}
