package wqs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ProjectionItemTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_sub_batch_id_means_no_play(String blank) {
        ProjectionItem item = item("b1", blank);

        assertThat(item.subBatchId()).isNull();
        assertThat(item.hasPlay()).isFalse();
        assertThat(item.isSessioned()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_batch_id_means_no_session(String blank) {
        ProjectionItem item = item(blank, "p1");

        assertThat(item.batchId()).isNull();
        assertThat(item.isSessioned()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void a_blank_cancels_means_not_a_cancellation(String blank) {
        ProjectionItem item = new ProjectionItem(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), 11,
                null, null, (short) 1, new BigDecimal("5.0000"), "EUR", null, blank, null);

        assertThat(item.cancels()).isNull();
        assertThat(item.isCancellation()).isFalse();
    }

    @Test
    void non_blank_ids_are_kept_verbatim() {
        ProjectionItem item = item(" b1", "p1 ");

        assertThat(item.batchId()).isEqualTo(" b1");
        assertThat(item.subBatchId()).isEqualTo("p1 ");
        assertThat(item.isSessioned()).isTrue();
        assertThat(item.hasPlay()).isTrue();
    }

    private static ProjectionItem item(String batchId, String subBatchId) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, "t1", Instant.parse("2034-05-10T10:00:00Z"), 11,
                batchId, subBatchId, (short) 1, new BigDecimal("5.0000"), "EUR", null, null, null);
    }
}
