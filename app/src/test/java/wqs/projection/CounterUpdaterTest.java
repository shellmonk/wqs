package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

class CounterUpdaterTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-10-17T14:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2034-10-18T01:00:00Z");
    private static final Instant NEXT_MONTH = Instant.parse("2034-11-02T01:00:00Z");

    @BeforeEach
    void setUp() {
        new PartitionManager(dsl()).ensurePartitionsAhead(YearMonth.of(2034, 10), 1);
        dsl().execute("delete from \"wqCountDay\"");
        dsl().execute("delete from \"wqCountMonth\"");
    }

    private void run(java.util.function.Consumer<CounterUpdater> action) {
        dsl().transaction(cfg -> action.accept(new CounterUpdater(DSL.using(cfg))));
    }

    @Test
    void an_item_counts_against_its_product_and_the_all_products_sentinel() {
        run(u -> u.countItem(tx("t1", 11)));

        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 11, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isEqualTo(1);
    }

    @Test
    void an_item_counts_in_every_view_its_source_feeds() {
        run(u -> u.countItem(tx("t1", 11)));

        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.CC, PageUnit.ITEM, 0, T0)).isEqualTo(1);
    }

    @Test
    void a_payment_counts_only_in_cc_and_only_against_the_sentinel() {
        ProjectionItem payment = new ProjectionItem(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null,
                (short) 9, new BigDecimal("50.0000"), "EUR", null, null, null);

        run(u -> u.countItem(payment));

        assertThat(dayCount(ViewKind.CC, PageUnit.ITEM, 0, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isZero();
        assertThat(rows("wqCountDay")).isEqualTo(1);
    }

    @Test
    void day_and_month_tiers_both_advance() {
        run(u -> {
            u.countItem(tx("t1", 11));
            u.countItem(tx("t2", 11));
        });

        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isEqualTo(2);
        assertThat(monthCount(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isEqualTo(2);
    }

    @Test
    void high_level_counts_are_tracked_separately_from_item_counts() {
        run(u -> {
            u.countItem(tx("t1", 11));
            u.countHighLevel(1L, ViewKind.WEB, 11, T0);
        });

        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
    }

    @Test
    void high_level_counting_is_per_view() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, 11, T0));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.CC, PageUnit.HIGH_LEVEL, 0, T0)).isZero();
    }

    @Test
    void uncounting_reduces_only_the_excl_cancelled_tally() {
        run(u -> u.countItem(tx("t1", 11)));

        run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.ITEM, 11, T0));

        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 11, T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.ITEM, 11, T0)).isZero();
        assertThat(monthExcl(ViewKind.WEB, PageUnit.ITEM, 0, T0)).isZero();
    }

    @Test
    void a_move_across_days_shifts_day_rows_but_not_the_month_row() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, 11, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, 11, NEXT_DAY, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, NEXT_DAY)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, NEXT_DAY)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, NEXT_DAY)).isEqualTo(1);
    }

    @Test
    void a_move_across_months_shifts_month_rows_too() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, 11, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, 11, NEXT_MONTH, true));

        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, NEXT_MONTH)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, NEXT_MONTH)).isEqualTo(1);
    }

    @Test
    void a_product_only_move_shifts_the_own_product_row_and_leaves_the_sentinel() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, 11, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, 12, T0, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 12, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
    }

    @Test
    void an_invisible_row_moves_only_cntAll() {
        run(u -> {
            u.countHighLevel(1L, ViewKind.WEB, 11, T0);
            u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0);
        });

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, 11, NEXT_DAY, false));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, NEXT_DAY)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, NEXT_DAY)).isZero();
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, NEXT_DAY)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isZero();
    }

    @Test
    void a_move_to_the_same_day_and_product_changes_nothing() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, 11, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, 11, T0.plusSeconds(60), true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
    }

    @Test
    void a_null_product_before_a_move_counts_the_sentinel_only_and_gains_the_new_product() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, null, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, null, T0, 11, T0, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(dayExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
        assertThat(monthExcl(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isEqualTo(1);
    }

    @Test
    void a_null_product_after_a_move_drops_the_old_product_and_keeps_the_sentinel() {
        run(u -> u.countHighLevel(1L, ViewKind.WEB, 11, T0));

        run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, null, NEXT_DAY, true));

        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isZero();
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, NEXT_DAY)).isEqualTo(1);
        assertThat(dayCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 11, T0)).isZero();
        assertThat(monthCount(ViewKind.WEB, PageUnit.HIGH_LEVEL, 0, T0)).isEqualTo(1);
        assertThat(rows("wqCountDay")).isEqualTo(3); // 11@T0 (0), 0@T0 (0), 0@NEXT_DAY (1)
    }

    @Test
    void decrementing_a_missing_row_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                run(u -> u.moveHighLevel(1L, ViewKind.WEB, 11, T0, 11, NEXT_DAY, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wqCountDay")
                .hasMessageContaining("productId=11");
    }

    @Test
    void uncounting_twice_throws_instead_of_clamping() {
        run(u -> u.countItem(tx("t1", 11)));
        run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.ITEM, 11, T0));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                run(u -> u.uncountExclCancelled(1L, ViewKind.WEB, PageUnit.ITEM, 11, T0)))
                .isInstanceOf(RuntimeException.class);
        assertThat(dayCount(ViewKind.WEB, PageUnit.ITEM, 11, T0)).isEqualTo(1);
    }

    @Test
    void the_check_constraint_rejects_negative_and_excl_above_all() {
        run(u -> u.countItem(tx("t1", 11)));

        for (String table : new String[] {"wqCountDay", "wqCountMonth"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dsl().execute(
                    "update \"" + table + "\" set \"cntAll\" = -1, \"cntExclCancelled\" = -1"))
                    .hasMessageContaining("check");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dsl().execute(
                    "update \"" + table + "\" set \"cntExclCancelled\" = \"cntAll\" + 1"))
                    .hasMessageContaining("check");
        }
    }

    private ProjectionItem tx(String id, Integer productId) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, T0, productId, "b1", "p1",
                (short) 1, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private int dayCount(ViewKind v, PageUnit u, int product, Instant at) {
        return counter("wqCountDay", "cntAll", v, u, product, at, false);
    }

    private int dayExcl(ViewKind v, PageUnit u, int product, Instant at) {
        return counter("wqCountDay", "cntExclCancelled", v, u, product, at, false);
    }

    private int monthCount(ViewKind v, PageUnit u, int product, Instant at) {
        return counter("wqCountMonth", "cntAll", v, u, product, at, true);
    }

    private int monthExcl(ViewKind v, PageUnit u, int product, Instant at) {
        return counter("wqCountMonth", "cntExclCancelled", v, u, product, at, true);
    }

    private int rows(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }

    /** Test-side verification only: reads one counter, 0 when the sparse row does not exist. */
    private int counter(String table, String column, ViewKind view, PageUnit unit, int product,
                        Instant at, boolean month) {
        java.time.LocalDate day = java.time.LocalDate.ofInstant(at, java.time.ZoneOffset.UTC);
        java.time.LocalDate period = month ? day.withDayOfMonth(1) : day;
        var record = dsl().fetchOne("""
                select coalesce(sum("%s"), 0)::int from "%s"
                where "userId" = 1 and "viewKind" = ? and "pageUnit" = ? and "productId" = ?
                  and "period" = ?
                """.formatted(column, table), (short) view.code(), unit.code(), product, period);
        return record.get(0, Integer.class);
    }
}
