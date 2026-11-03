package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.projection.source.StreamPayload;

class StreamPayloadMapperTest {

    private static final Instant WHEN = Instant.parse("2033-07-05T09:00:00Z");

    private final StreamPayloadMapper mapper = new StreamPayloadMapperImpl();

    private static StreamPayload payload(String id, String type, String reference, Map<String, String> detail) {
        return new StreamPayload(
                id, 1, 7L, WHEN, type, "TIKI", "instants", "b-1", "p-1",
                new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"), reference, detail);
    }

    @Test
    void maps_structural_fields_and_keeps_the_type_code() {

        ProjectionTransaction transaction = mapper.toTransaction(payload("tx-1", "TT_BUY", "tx-1", Map.of()));

        assertThat(transaction.sourceId()).isEqualTo("tx-1");
        assertThat(transaction.userId()).isEqualTo(7L);
        assertThat(transaction.source()).isEqualTo(SourceKind.TRANSACTION);
        assertThat(transaction.occurredAt()).isEqualTo(WHEN);
        assertThat(transaction.productId()).isEqualTo("TIKI");
        assertThat(transaction.productType()).isEqualTo("instants");
        assertThat(transaction.batchId()).isEqualTo("b-1");
        assertThat(transaction.subBatchId()).isEqualTo("p-1");
        assertThat(transaction.type()).as("the wire code, as-is").isEqualTo("TT_BUY");
        assertThat(transaction.amount()).isEqualByComparingTo("5.0000");
        assertThat(transaction.currency()).isEqualTo("EUR");
        assertThat(transaction.balanceAfter()).isEqualByComparingTo("95.0000");
        assertThat(transaction.cancels()).isNull();
        assertThat(transaction.detailJson()).isNull();
    }

    @Test
    void detail_becomes_detail_json() {

        ProjectionTransaction transaction = mapper.toTransaction(
                payload("tx-2", "TT_BUY", "tx-2", Map.of("toDescription", "WITHDRAWAL*5208")));

        assertThat(transaction.detailJson()).contains("\"toDescription\":\"WITHDRAWAL*5208\"");
    }

    @Test
    void cancel_type_with_foreign_reference_cancels() {

        ProjectionTransaction transaction = mapper.toTransaction(payload("200", "TT_CANCEL", "100", Map.of()));

        assertThat(transaction.isCancellation()).isTrue();
        assertThat(transaction.cancels()).isEqualTo("100");
    }

    @Test
    void every_cancel_type_cancels() {
        for (String type : new String[] {"TT_CANCEL", "CANCEL", "TT_TOMBSTONE"}) {
            assertThat(mapper.toTransaction(payload("200", type, "100", Map.of())).cancels())
                    .as(type).isEqualTo("100");
        }
    }

    @Test
    void cancel_type_self_reference_is_not_a_cancel() {

        assertThat(mapper.toTransaction(payload("200", "TT_CANCEL", "200", Map.of())).cancels()).isNull();
    }

    @Test
    void cancel_type_without_reference_is_not_a_cancel() {

        assertThat(mapper.toTransaction(payload("200", "TT_CANCEL", null, Map.of())).cancels()).isNull();
        assertThat(mapper.toTransaction(payload("200", "TT_CANCEL", "", Map.of())).cancels()).isNull();
    }

    @Test
    void undo_and_rollback_types_are_not_cancels() {
        for (String type : new String[] {"TT_BUY_UNDO", "BUY_ROLLBACK", "TT_PRIZE_IN", "PRIZE"}) {
            assertThat(mapper.toTransaction(payload("200", type, "100", Map.of())).cancels())
                    .as(type).isNull();
        }
    }

    @Test
    void undo_type_is_not_a_cancel() {

        assertThat(mapper.toTransaction(payload("200", "TT_CANCEL_UNDO", "100", Map.of())).cancels()).isNull();
    }

    @Test
    void a_non_player_owner_still_maps() {
        StreamPayload operator = new StreamPayload(
                "tx-9", 2, 7L, WHEN, "TT_BUY", null, null, null, null,
                BigDecimal.ONE, "EUR", null, "tx-9", Map.of());

        assertThat(mapper.toTransaction(operator).userId()).isEqualTo(7L);
    }
}
