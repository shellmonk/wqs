package wqs.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

@SpringBootTest
class HighLevelUpdaterWiringTest extends PostgresTestBase {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private HighLevelUpdater updater;

    @Test
    void applying_an_item_outside_a_transaction_is_rejected() {
        ProjectionItem item = new ProjectionItem(
                1L, SourceKind.PAYMENT, "pay1", Instant.parse("2034-08-03T09:00:00Z"), null, null,
                null, null, TxTypes.NEUTRAL, new BigDecimal("50.0000"), "EUR", null, null, null);

        assertThatThrownBy(() -> updater.applyItem(item, RollupResult.NONE))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
