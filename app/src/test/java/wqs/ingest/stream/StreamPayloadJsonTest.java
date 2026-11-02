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

    /** The valid base message with the given fields (raw JSON values) overridden. */
    private static String with(String field, String jsonValue, String... more) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("transactionId", "\"tx-1\"");
        fields.put("userId", "7");
        fields.put("occurredAt", "\"2033-09-02T12:00:00Z\"");
        fields.put("type", "1");
        fields.put("amount", "1.0000");
        fields.put("currency", "\"EUR\"");
        fields.put(field, jsonValue);
        for (int k = 0; k < more.length; k += 2) {
            fields.put(more[k], more[k + 1]);
        }
        return fields.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    @Test
    void integral_fields_accept_integral_numbers_and_integral_text() {
        StreamPayload p = parse(with("userId", "\"42\"", "type", "\"3\""));

        assertThat(p.userId()).isEqualTo(42L);
        assertThat(p.type()).isEqualTo((short) 3);
    }

    @Test
    void non_integral_or_non_numeric_integral_fields_are_rejected_naming_the_field() {
        for (String bad : new String[] {"\"abc\"", "1.5", "true", "[]", "{}", "\"\"", "7.0"}) {
            assertThatThrownBy(() -> parse(with("userId", bad)))
                    .as("userId=" + bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userId");
        }
        assertThatThrownBy(() -> parse(with("productId", "\"x\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productId");
        assertThatThrownBy(() -> parse(with("type", "false")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
    }

    @Test
    void integral_fields_out_of_range_are_rejected_never_truncated() {
        assertThatThrownBy(() -> parse(with("type", "65536")))   // would wrap to 0 as a short
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> parse(with("type", "32768")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> parse(with("productId", "4294967296")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productId");
        assertThatThrownBy(() -> parse(with("userId", "9223372036854775808")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> parse(with("userId", "\"" + "9".repeat(5000) + "\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userId");
        assertThat(parse(with("type", "32767")).type()).isEqualTo(Short.MAX_VALUE);
        assertThat(parse(with("userId", "9223372036854775807")).userId()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void occurred_at_parse_failures_name_the_field() {
        assertThatThrownBy(() -> parse(with("occurredAt", "\"yesterday\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> parse(with("occurredAt", "12345")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void money_within_numeric_20_4_is_accepted_at_the_edges() {
        assertThat(parse(with("amount", "9999999999999999.9999")).amount())
                .isEqualTo(new BigDecimal("9999999999999999.9999"));
        assertThat(parse(with("amount", "\"-9999999999999999.9999\"")).amount())
                .isEqualTo(new BigDecimal("-9999999999999999.9999"));
        // Trailing zeros beyond scale 4 are exactly representable, so not a violation.
        assertThat(parse(with("amount", "\"1.50000\"")).amount()).isEqualByComparingTo("1.5");
    }

    @Test
    void money_with_too_many_decimals_or_digits_is_rejected_naming_the_field() {
        assertThatThrownBy(() -> parse(with("amount", "0.00001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount", "\"1.23456\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount", "10000000000000000")))   // 17 integer digits
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("balanceAfter", "\"-10000000000000000.0\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("balanceAfter");
    }

    @Test
    void money_exponent_bombs_and_oversized_text_are_rejected_without_expanding() {
        assertThatThrownBy(() -> parse(with("amount", "1e999999999")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount", "\"1e999999999\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount", "1e-999999999")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("balanceAfter", "\"" + "1".repeat(500) + "\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("balanceAfter");
    }

    @Test
    void money_that_is_neither_number_nor_numeric_text_is_rejected() {
        for (String bad : new String[] {"true", "[]", "{}", "\"abc\"", "\"\""}) {
            assertThatThrownBy(() -> parse(with("amount", bad)))
                    .as("amount=" + bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amount");
        }
    }

    @Test
    void text_fields_must_be_json_strings() {
        for (String bad : new String[] {"{}", "true", "5", "[]"}) {
            assertThatThrownBy(() -> parse(with("currency", bad)))
                    .as("currency=" + bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
            assertThatThrownBy(() -> parse(with("transactionId", bad)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionId");
        }
        assertThatThrownBy(() -> parse(with("batchId", "5")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchId");
        assertThatThrownBy(() -> parse(with("subBatchId", "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subBatchId");
        assertThatThrownBy(() -> parse(with("cancelsTransactionId", "true")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cancelsTransactionId");
    }
}
