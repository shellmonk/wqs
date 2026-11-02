package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.ingest.stream.StreamPayload;

class StreamPayloadMapperTest {

    private final StreamPayloadMapper mapper = new StreamPayloadMapperImpl();

    @Test
    void maps_structural_fields() {
        Instant occurredAt = Instant.parse("2033-07-05T09:00:00Z");
        StreamPayload payload = new StreamPayload(
                "tx-1", 7L, occurredAt, 11,
                "b-1", "p-1", (short) 1, new BigDecimal("5.0000"), "EUR",
                new BigDecimal("95.0000"), null, Map.of());

        ProjectionItem item = mapper.toItem(payload);

        assertThat(item.sourceId()).isEqualTo("tx-1");
        assertThat(item.userId()).isEqualTo(7L);
        assertThat(item.source()).isEqualTo(SourceKind.TRANSACTION);
        assertThat(item.occurredAt()).isEqualTo(occurredAt);
        assertThat(item.productId()).isEqualTo(11);
        assertThat(item.batchId()).isEqualTo("b-1");
        assertThat(item.subBatchId()).isEqualTo("p-1");
        assertThat(item.type()).isEqualTo((short) 1);
        assertThat(item.amount()).isEqualByComparingTo("5.0000");
        assertThat(item.currency()).isEqualTo("EUR");
        assertThat(item.balanceAfter()).isEqualByComparingTo("95.0000");
        assertThat(item.cancels()).isNull();
        assertThat(item.detailJson()).isNull();
    }

    @Test
    void unknown_extra_fields_become_detail_json() {
        StreamPayload payload = new StreamPayload(
                "tx-2", 7L, Instant.parse("2033-07-05T09:00:00Z"), 11,
                "b-1", "p-1", (short) 1, new BigDecimal("5.0000"), "EUR", null, null,
                Map.of("gameCode", "CRZ8", "lines", "5"));

        ProjectionItem item = mapper.toItem(payload);

        assertThat(item.detailJson()).contains("\"gameCode\":\"CRZ8\"").contains("\"lines\":\"5\"");
    }

    @Test
    void no_extra_fields_yields_null_detail() {
        StreamPayload payload = new StreamPayload(
                "tx-3", 7L, Instant.parse("2033-07-05T09:00:00Z"), 11,
                "b-1", "p-1", (short) 1, new BigDecimal("5.0000"), "EUR", null, null, Map.of());

        assertThat(mapper.toItem(payload).detailJson()).isNull();
    }

    @Test
    void a_cancellation_carries_the_cancelled_source_id() {
        StreamPayload payload = new StreamPayload(
                "tx-4", 7L, Instant.parse("2033-07-05T09:00:00Z"), 11,
                "b-1", "p-1", (short) 2, new BigDecimal("-5.0000"), "EUR", null, "tx-1", Map.of());

        ProjectionItem item = mapper.toItem(payload);

        assertThat(item.isCancellation()).isTrue();
        assertThat(item.cancels()).isEqualTo("tx-1");
    }
}
