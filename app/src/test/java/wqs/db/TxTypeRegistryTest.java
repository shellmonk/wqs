package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.domain.MoneyDirection;

/** Plan D2: the data-driven transaction-type table and its cached registry. */
class TxTypeRegistryTest extends PostgresTestBase {

    // Bound the way the app binds its DSLContext: to the Spring-managed connection, so a registry
    // call made inside an outer transaction would join it unless the registry isolates itself.
    private static final DataSourceTransactionManager TM = new DataSourceTransactionManager(dataSource());
    private static final DSLContext TX_DSL =
            DSL.using(new TransactionAwareDataSourceProxy(dataSource()), SQLDialect.POSTGRES);

    private static TxTypeRegistry registry() {
        return new TxTypeRegistry(TX_DSL, TM);
    }

    @Test
    void seeded_codes_resolve_with_their_direction() {
        TxTypeRegistry registry = registry();

        short buy = registry.idOf("TT_BUY");
        short prize = registry.idOf("PRIZE");
        short out = registry.idOf("TT_TRANSFER_OUT");

        assertThat(registry.directionOf(buy)).isEqualTo(MoneyDirection.PLAY);
        assertThat(registry.directionOf(prize)).isEqualTo(MoneyDirection.WIN);
        assertThat(registry.directionOf(out)).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(registry.codeOf(buy)).isEqualTo("TT_BUY");
        assertThat(buy).isPositive();
    }

    @Test
    void unknown_code_is_inserted_once_as_neutral_and_stable_across_registries() {
        String code = "TT_UNSEEN_" + UUID.randomUUID();
        int before = rowCount();

        short first = registry().idOf(code);
        short again = registry().idOf(code);
        TxTypeRegistry fresh = registry();

        assertThat(again).isEqualTo(first);
        assertThat(fresh.idOf(code)).isEqualTo(first);
        assertThat(fresh.directionOf(first)).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(fresh.codeOf(first)).isEqualTo(code);
        assertThat(first).as("0 is the ALL sentinel").isPositive();
        assertThat(rowCount()).isEqualTo(before + 1);
    }

    /**
     * The id is persisted outside the caller's transaction: an id handed to a projection
     * transaction that then rolls back must still name the same code after a restart.
     */
    @Test
    void an_inserted_code_survives_the_callers_rollback() {
        String code = "TT_ROLLED_BACK_" + UUID.randomUUID();
        TxTypeRegistry registry = registry();

        Short id = new TransactionTemplate(TM).execute(status -> {
            short assigned = registry.idOf(code);
            status.setRollbackOnly();
            return assigned;
        });

        assertThat(registry().codeOf(id)).isEqualTo(code);
    }

    @Test
    void ids_of_resolves_known_codes_and_never_inserts() {
        TxTypeRegistry registry = registry();
        String unknown = "TT_NEVER_" + UUID.randomUUID();
        int before = rowCount();

        var ids = registry.idsOf(List.of("TT_BUY", "PRIZE", unknown));

        assertThat(ids).containsExactlyInAnyOrder(registry.idOf("TT_BUY"), registry.idOf("PRIZE"));
        assertThat(registry.idsOf(List.of(unknown))).isEmpty();
        assertThat(rowCount()).isEqualTo(before);
    }

    @Test
    void an_unknown_type_id_is_a_bug_not_a_default() {
        assertThatThrownBy(() -> registry().directionOf((short) 32000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32000");
    }

    private static int rowCount() {
        return dsl().fetchCount(DSL.table(DSL.name("wqTxType")));
    }
}
