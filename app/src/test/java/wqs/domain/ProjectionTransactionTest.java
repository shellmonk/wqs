package wqs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wqs.gen.TxTypes;

class ProjectionTransactionTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_sub_batch_id_means_no_play(String blank) {
        ProjectionTransaction transaction = transaction("b1", blank);

        assertThat(transaction.subBatchId()).isNull();
        assertThat(transaction.hasPlay()).isFalse();
        assertThat(transaction.isSessioned()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_batch_id_means_no_session(String blank) {
        ProjectionTransaction transaction = transaction(blank, "p1");

        assertThat(transaction.batchId()).isNull();
        assertThat(transaction.isSessioned()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_cancels_means_not_a_cancellation(String blank) {
        ProjectionTransaction transaction = new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), "P11",
                "instants", null, null, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, blank, null);

        assertThat(transaction.cancels()).isNull();
        assertThat(transaction.isCancellation()).isFalse();
    }

    /** '' is the counters' ALL-product-types sentinel, so it must be unreachable from input. */
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_product_id_and_type_mean_no_product(String blank) {
        ProjectionTransaction transaction = new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), blank,
                blank, null, null, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);

        assertThat(transaction.productId()).isNull();
        assertThat(transaction.productType()).isNull();
    }

    /** '' is the counters' ALL-transaction-types sentinel, so a blank (or missing) type code is rejected. */
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_type_code_is_rejected(String type) {
        assertThatThrownBy(() -> new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), "P11",
                "instants", null, null, type, new BigDecimal("5.0000"), "EUR", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction type code");
        assertThatThrownBy(() -> new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), "P11",
                "instants", null, null, null, new BigDecimal("5.0000"), "EUR", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_blank_ids_are_kept_verbatim() {
        ProjectionTransaction transaction = transaction(" b1", "p1 ");

        assertThat(transaction.batchId()).isEqualTo(" b1");
        assertThat(transaction.subBatchId()).isEqualTo("p1 ");
        assertThat(transaction.isSessioned()).isTrue();
        assertThat(transaction.hasPlay()).isTrue();
    }

    private static ProjectionTransaction transaction(String batchId, String subBatchId) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), "P11",
                "instants", batchId, subBatchId, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }
}
