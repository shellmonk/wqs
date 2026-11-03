package wqs.gen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import wqs.db.PostgresTestBase;
import wqs.db.TxTypeRegistry;
import wqs.domain.MoneyDirection;

class TxTypesTest extends PostgresTestBase {

    @Test
    void test_constants_name_the_seeded_types() {
        TxTypeRegistry registry = new TxTypeRegistry(jdbc(), new DataSourceTransactionManager(dataSource()));

        assertThat(registry.codeOf(TxTypes.PLAY)).isEqualTo("TT_BUY");
        assertThat(registry.codeOf(TxTypes.WIN)).isEqualTo("PRIZE");
        assertThat(registry.codeOf(TxTypes.NEUTRAL)).isEqualTo("TT_TRANSFER_OUT");
        assertThat(registry.codeOf(TxTypes.CANCEL)).isEqualTo("TT_CANCEL");
        assertThat(registry.directionOf(TxTypes.PLAY)).isEqualTo(MoneyDirection.PLAY);
        assertThat(registry.directionOf(TxTypes.WIN)).isEqualTo(MoneyDirection.WIN);
        assertThat(registry.directionOf(TxTypes.NEUTRAL)).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(registry.directionOf(TxTypes.CANCEL)).isEqualTo(MoneyDirection.NEUTRAL);
    }
}
