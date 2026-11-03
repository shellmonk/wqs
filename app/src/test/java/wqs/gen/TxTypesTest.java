package wqs.gen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import wqs.domain.MoneyDirection;
import wqs.domain.TransactionType;

class TxTypesTest {

    @Test
    void test_constants_name_known_types_with_their_direction() {
        assertThat(TransactionType.valueOf(TxTypes.PLAY).direction()).isEqualTo(MoneyDirection.PLAY);
        assertThat(TransactionType.valueOf(TxTypes.WIN).direction()).isEqualTo(MoneyDirection.WIN);
        assertThat(TransactionType.valueOf(TxTypes.NEUTRAL).direction()).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(TransactionType.valueOf(TxTypes.CANCEL).direction()).isEqualTo(MoneyDirection.NEUTRAL);
    }
}
