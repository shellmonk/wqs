package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

/**
 * The owner-month counts (plan D5): one "wqOwnerMonth" row per owner, UTC month and EXACT combination;
 * no "all" rows; strict decrements; rows deleted at zero.
 */
class CounterUpdaterTest extends PostgresTestBase {

    private static final short P = 1;
    private static final short TX = 0;
    private static final short ENTRY = 1;
    private static final short TRANSACTION = 1;
    private static final short PAYMENT = 4;
    private static final Instant T0 = Instant.parse("2034-10-17T14:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2034-10-18T01:00:00Z");
    private static final Instant NEXT_MONTH = Instant.parse("2034-11-02T01:00:00Z");
    private static final LocalDate OCT = LocalDate.parse("2034-10-01");
    private static final LocalDate NOV = LocalDate.parse("2034-11-01");

    @BeforeEach
    void setUp() {
        db().execute("delete from \"wqOwnerMonth\"");
    }

    private void run(java.util.function.Consumer<CounterUpdater> action) {
        inTx().executeWithoutResult(status -> action.accept(new CounterUpdater(jdbc())));
    }

    @Test
    void a_transaction_counts_once_at_its_exact_combination_and_month() {
        run(u -> u.countTransaction(tx("t1", "t11")));

        assertThat(rows()).containsExactly(row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 1, 1));
    }

    @Test
    void a_transaction_without_a_product_type_counts_under_none_and_a_payment_under_its_own_source() {
        ProjectionTransaction payment = new ProjectionTransaction(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("50.0000"), "EUR", null, null, null);

        run(u -> u.countTransaction(payment));

        assertThat(rows()).containsExactly(row(OCT, PAYMENT, TX, "", TxTypes.NEUTRAL, false, 1, 1));
    }

    @Test
    void transactions_of_one_month_share_a_row_and_another_month_has_its_own() {
        run(u -> {
            u.countTransaction(tx("t1", "t11"));
            u.countTransaction(tx("t2", "t11", NEXT_DAY));
            u.countTransaction(tx("t3", "t11", NEXT_MONTH));
        });

        assertThat(rows()).containsExactly(
                row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 2, 2),
                row(NOV, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 1, 1));
    }

    @Test
    void a_zero_amount_transaction_is_counted_apart() {
        run(u -> {
            u.countTransaction(tx("t1", "t11"));
            u.countTransaction(new ProjectionTransaction(1L, SourceKind.TRANSACTION, "z1", T0, "P-t11", "t11",
                    null, null, TxTypes.PLAY, new BigDecimal("0.0000"), "EUR", null, null, null));
        });

        assertThat(rows()).containsExactly(
                row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 1, 1),
                row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, true, 1, 1));
    }

    @Test
    void entries_are_counted_apart_from_transactions_with_no_type() {
        run(u -> {
            u.countTransaction(tx("t1", "t11"));
            u.countEntry(P, 1L, T0, TRANSACTION, "t11", false);
        });

        assertThat(rows()).containsExactly(
                row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 1, 1),
                row(OCT, TRANSACTION, ENTRY, "t11", "", false, 1, 1));
    }

    @Test
    void uncounting_reduces_only_the_excl_cancelled_tally() {
        run(u -> u.countTransaction(tx("t1", "t11")));
        run(u -> u.uncountTransactionExcl(P, 1L, T0, TRANSACTION, "t11", TxTypes.PLAY, false));
        run(u -> u.countEntry(P, 1L, T0, TRANSACTION, null, false));
        run(u -> u.uncountEntryExcl(P, 1L, T0, TRANSACTION, null, false));

        assertThat(rows()).containsExactly(
                row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 1, 0),
                row(OCT, TRANSACTION, ENTRY, "", "", false, 1, 0));
    }

    @Test
    void a_session_move_across_months_deletes_the_row_it_empties_so_state_is_path_independent() {
        run(u -> u.countEntry(P, 1L, T0, TRANSACTION, "t11", false));
        run(u -> u.moveSession(P, 1L, T0, TRANSACTION, "t11", true, NEXT_MONTH, TRANSACTION, "t11", true));

        assertThat(rows()).containsExactly(row(NOV, TRANSACTION, ENTRY, "t11", "", false, 1, 1));
    }

    @Test
    void a_move_inside_one_month_and_product_changes_nothing() {
        run(u -> u.countEntry(P, 1L, T0, TRANSACTION, "t11", false));
        run(u -> u.moveSession(P, 1L, NEXT_DAY, TRANSACTION, "t11", true, T0, TRANSACTION, "t11", true));

        assertThat(rows()).containsExactly(row(OCT, TRANSACTION, ENTRY, "t11", "", false, 1, 1));
    }

    @Test
    void a_relabel_moves_between_product_types_and_first_sources() {
        run(u -> u.countEntry(P, 1L, T0, TRANSACTION, "t11", false));
        run(u -> u.countEntry(P, 1L, T0, TRANSACTION, "t11", false));
        run(u -> u.moveSession(P, 1L, T0, TRANSACTION, "t11", true, T0, (short) 3, null, true));

        assertThat(rows()).containsExactly(
                row(OCT, TRANSACTION, ENTRY, "t11", "", false, 1, 1),
                row(OCT, (short) 3, ENTRY, "", "", false, 1, 1));
    }

    @Test
    void an_invisible_session_moves_only_cnt_and_a_revive_restores_only_excl() {
        run(u -> u.countEntry(P, 1L, T0, TRANSACTION, "t11", false));
        run(u -> u.uncountEntryExcl(P, 1L, T0, TRANSACTION, "t11", false));
        run(u -> u.countEntry(P, 1L, NEXT_MONTH, TRANSACTION, "t11", false));
        run(u -> u.moveSession(P, 1L, T0, TRANSACTION, "t11", false, NEXT_MONTH, TRANSACTION, "t11", false));
        assertThat(rows()).containsExactly(row(NOV, TRANSACTION, ENTRY, "t11", "", false, 2, 1));

        run(u -> u.moveSession(P, 1L, NEXT_MONTH, TRANSACTION, "t11", false, NEXT_MONTH, TRANSACTION, "t11", true));
        assertThat(rows()).containsExactly(row(NOV, TRANSACTION, ENTRY, "t11", "", false, 2, 2));
    }

    @Test
    void decrementing_a_missing_row_throws_naming_the_key() {
        assertThatThrownBy(() -> run(u -> u.moveSession(P, 1L, T0, TRANSACTION, "t11", true,
                NEXT_MONTH, TRANSACTION, "t11", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wqOwnerMonth").hasMessageContaining("month=2034-10-01")
                .hasMessageContaining("productType='t11'");
        assertThatThrownBy(() -> run(u -> u.uncountTransactionExcl(P, 1L, T0, TRANSACTION, "t11", TxTypes.PLAY, false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isEmpty();
    }

    @Test
    void uncounting_twice_throws_instead_of_clamping() {
        run(u -> u.countTransaction(tx("t1", "t11")));
        run(u -> u.uncountTransactionExcl(P, 1L, T0, TRANSACTION, "t11", TxTypes.PLAY, false));

        assertThatThrownBy(() -> run(u -> u.uncountTransactionExcl(P, 1L, T0, TRANSACTION, "t11", TxTypes.PLAY, false)))
                .isInstanceOf(RuntimeException.class);
        assertThat(rows()).containsExactly(row(OCT, TRANSACTION, TX, "t11", TxTypes.PLAY, false, 1, 0));
    }

    @Test
    void the_check_constraints_reject_negative_excl_above_cnt_and_a_typed_entry() {
        run(u -> u.countTransaction(tx("t1", "t11")));
        assertThatThrownBy(() -> db().execute("update \"wqOwnerMonth\" set \"cntExclCancelled\" = 2"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db().execute("update \"wqOwnerMonth\" set \"cnt\" = -1, \"cntExclCancelled\" = -1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db().execute("update \"wqOwnerMonth\" set \"unit\" = 1"))
                .as("an ENTRY row has no type").isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db().execute("update \"wqOwnerMonth\" set \"month\" = date '2034-10-02'"))
                .as("a month is its first day").isInstanceOf(RuntimeException.class);
    }

    @Test
    void owners_are_kept_apart() {
        run(u -> {
            u.countEntry(P, 1L, T0, TRANSACTION, "t11", false);
            u.countEntry((short) 2, 1L, T0, TRANSACTION, "t11", false);
        });

        assertThat(db().column("select \"ownerType\" || '-' || \"userId\" from \"wqOwnerMonth\" order by 1", String.class))
                .containsExactly("1-1", "2-1");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String row(LocalDate month, short source, short unit, String productType, String type,
                              boolean zero, long cnt, long excl) {
        return month + "|" + source + "|" + unit + "|" + productType + "|" + type + "|" + zero + "|" + cnt + "|" + excl;
    }

    private static List<String> rows() {
        return db().column("""
                select "month" || '|' || "source" || '|' || "unit" || '|' || "productType" || '|' || "type" || '|'
                       || "zeroAmount" || '|' || "cnt" || '|' || "cntExclCancelled"
                from "wqOwnerMonth" where "ownerType" = 1 and "userId" = 1
                order by "month", "source", "unit", "productType", "type", "zeroAmount"
                """, String.class);
    }

    private static ProjectionTransaction tx(String id, String productType) {
        return tx(id, productType, T0);
    }

    private static ProjectionTransaction tx(String id, String productType, Instant at) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, at, "P-" + productType, productType,
                null, null, TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
