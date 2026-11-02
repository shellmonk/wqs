package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StreamPayloadJsonTest {

    private static final String BASE =
            "\"transactionId\":\"tx-1\",\"userId\":7,\"occurredAt\":\"2033-09-02T12:00:00Z\","
                    + "\"type\":1,\"amount\":0.1000,\"currency\":\"EUR\"";

    private final ObjectMapper mapper = new ObjectMapper();

    private StreamPayload parse(String json) {
        return RabbitStreamSource.parse(mapper, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parses_known_fields_with_iso_instant_and_exact_decimal() {
        StreamPayload p = parse("{" + BASE + ",\"productId\":11,\"balanceAfter\":12.50}");

        assertThat(p.transactionId()).isEqualTo("tx-1");
        assertThat(p.userId()).isEqualTo(7L);
        assertThat(p.occurredAt()).isEqualTo(Instant.parse("2033-09-02T12:00:00Z"));
        assertThat(p.type()).isEqualTo((short) 1);
        assertThat(p.amount()).isEqualTo(new BigDecimal("0.1000"));
        assertThat(p.amount().scale()).isEqualTo(4);
        assertThat(p.balanceAfter()).isEqualTo(new BigDecimal("12.50"));
        assertThat(p.productId()).isEqualTo(11);
        assertThat(p.extra()).isEmpty();
    }

    @Test
    void unknown_scalar_and_object_fields_land_in_extra() {
        StreamPayload p = parse("{" + BASE + ",\"channel\":\"web\",\"n\":5,\"meta\":{\"a\":1}}");

        assertThat(p.extra())
                .containsEntry("channel", "web")
                .containsEntry("n", "5")
                .containsEntry("meta", "{\"a\":1}")
                .hasSize(3);
    }

    @Test
    void null_unknown_fields_are_skipped() {
        StreamPayload p = parse("{" + BASE + ",\"channel\":null}");

        assertThat(p.extra()).isEmpty();
    }

    @Test
    void missing_optional_fields_are_null() {
        StreamPayload p = parse("{" + BASE + ",\"batchId\":null}");

        assertThat(p.productId()).isNull();
        assertThat(p.batchId()).isNull();
        assertThat(p.subBatchId()).isNull();
        assertThat(p.balanceAfter()).isNull();
        assertThat(p.cancelsTransactionId()).isNull();
    }

    @Test
    void missing_required_field_names_the_field() {
        String json = "{\"transactionId\":\"tx-1\",\"userId\":7,"
                + "\"occurredAt\":\"2033-09-02T12:00:00Z\",\"type\":1,\"currency\":\"EUR\"}";

        assertThatThrownBy(() -> parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
