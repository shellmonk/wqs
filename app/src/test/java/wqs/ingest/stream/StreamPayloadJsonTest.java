package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import wqs.projection.source.StreamPayload;

/** The real walAccountTransactions format (spec 4.1): golden samples plus the strictness rules. */
class StreamPayloadJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private StreamPayload parse(String json) {
        return RabbitStreamSource.parse(mapper, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String resource(String name) {
        try (var in = StreamPayloadJsonTest.class.getResourceAsStream("/stream/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void tt_transfer_out_sample_decodes() {
        StreamPayload p = parse(resource("tt-transfer-out.json"));

        assertThat(p.id()).isEqualTo("5962");
        assertThat(p.userType()).isEqualTo(1);
        assertThat(p.userId()).isEqualTo(1717L);
        assertThat(p.created()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465648Z"));
        assertThat(p.type()).isEqualTo("TT_TRANSFER_OUT");
        assertThat(p.amount()).isEqualByComparingTo("-12");
        assertThat(p.currency()).isEqualTo("EUR");
        assertThat(p.totalPostBalance()).isEqualByComparingTo("178");
        assertThat(p.productId()).isNull();
        assertThat(p.productType()).isNull();
        assertThat(p.batchId()).isNull();
        assertThat(p.subBatchId()).isNull();
        assertThat(p.transactionReference()).isEqualTo("5962");
        assertThat(p.detail())
                .containsEntry("references.paymentId", "6429")
                .containsEntry("toDescription", "WITHDRAWAL*5208")
                .containsEntry("fromDescription", "AT_MAIN")
                .containsEntry("account", "AT_MAIN-0")
                .containsEntry("accountContext.accountType", "AT_MAIN")
                .containsEntry("accountContext.accountNumber", "0")
                .containsEntry("monetary", "true")
                .containsEntry("createdContext.channel", "WEB")
                .containsEntry("createdContext.issued", "2026-10-29T11:22:04.465648Z")
                .containsEntry("batchContext.batchEnded", "false")
                .containsEntry("amount.reservedAmount", "0")
                .containsEntry("amount.nonWithdrawableAmount", "0")
                .doesNotContainKey("totalPostBalance")
                .doesNotContainKey("references.claimId")   // empty strings are dropped
                .doesNotContainKey("subtype");
    }

    @Test
    void prize_zero_sample_decodes() {
        StreamPayload p = parse(resource("prize-zero.json"));

        assertThat(p.id()).isEqualTo("5922");
        assertThat(p.userType()).isEqualTo(1);
        assertThat(p.userId()).isEqualTo(1945L);
        assertThat(p.type()).isEqualTo("PRIZE");
        assertThat(p.productId()).isEqualTo("TIKIKALACABANA");
        assertThat(p.productType()).isEqualTo("instants");
        assertThat(p.batchId()).isEqualTo("GS-ur5z5sgfr5de5flhjd6ycryf6u");
        assertThat(p.subBatchId()).isEqualTo("PS-62pflumf4bcz7jdtfc3wl5yqfm");
        assertThat(p.amount()).isEqualByComparingTo("0");
        assertThat(p.created()).isEqualTo(Instant.parse("2026-10-26T11:36:23.049062Z"));
        assertThat(p.detail())
                .containsEntry("description", "Payout Play Step")
                .containsEntry("references.extTrxId", "PS-62pflumf4bcz7jdtfc3wl5yqfm-1-prize")
                .containsEntry("references.extSystemId", "PDS")
                .containsEntry("batchContext.batchEnded", "true")
                .containsEntry("createdContext.issued", "2026-10-26T11:36:17.357Z");
    }

    @Test
    void unknown_top_level_field_is_not_in_detail() {
        StreamPayload p = parse(with("cardNumber", "\"4111111111111111\"", "nested", "{\"secret\":\"x\"}"));

        assertThat(p.detail()).isEmpty();
        assertThat(p.detail().toString()).doesNotContain("4111").doesNotContain("secret");
    }

    @Test
    void an_unknown_field_inside_an_allowlisted_object_is_not_in_detail() {
        StreamPayload p = parse(with("accountContext.iban", "\"DE00\"", "accountContext.accountType", "\"AT_MAIN\""));

        assertThat(p.detail()).containsOnlyKeys("accountContext.accountType");
    }

    @Test
    void owner_id_other_than_player_decodes() {
        StreamPayload p = parse(with("userContext.ownerId", "\"3-77\""));

        assertThat(p.userType()).isEqualTo(3);
        assertThat(p.userId()).isEqualTo(77L);
    }

    @Test
    void owner_id_without_dash_is_poison() {
        assertThatThrownBy(() -> parse(with("userContext.ownerId", "\"1717\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");
    }

    @Test
    void non_numeric_owner_is_poison() {
        for (String bad : new String[] {"\"x-1\"", "\"1-y\"", "\"-1\"", "\"1-\"", "\"1--5\"", "\"1-+5\"",
                "\"1-1.5\"", "\"1- 5\"", "\"\"", "\"1-9223372036854775808\"",
                "\"2147483648-1\"", "\"1-" + "9".repeat(5000) + "\""}) {
            assertThatThrownBy(() -> parse(with("userContext.ownerId", bad)))
                    .as("ownerId=" + bad.substring(0, Math.min(30, bad.length())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");
        }
        assertThat(parse(with("userContext.ownerId", "\"1-9223372036854775807\"")).userId())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void non_string_owner_id_is_poison() {
        for (String bad : new String[] {"1717", "{}", "true", "[]"}) {
            assertThatThrownBy(() -> parse(with("userContext.ownerId", bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");
        }
    }

    @Test
    void missing_required_field_names_the_field() {
        for (String path : new String[] {
                "id", "type", "userContext.ownerId", "createdContext.created", "amount.amount", "amount.currency",
                "userContext", "createdContext", "amount"}) {
            assertThatThrownBy(() -> parse(without(path)))
                    .as(path).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(path.substring(path.lastIndexOf('.') + 1));
        }
    }

    @Test
    void context_objects_must_be_objects() {
        for (String path : new String[] {"userContext", "createdContext", "amount", "batchContext", "references"}) {
            assertThatThrownBy(() -> parse(with(path, "\"x\"")))
                    .as(path).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(path);
        }
    }

    @Test
    void optional_fields_may_be_absent_or_null() {
        StreamPayload p = parse(without("productId", "productType", "batchContext", "references", "totalPostBalance"));

        assertThat(p.productId()).isNull();
        assertThat(p.batchId()).isNull();
        assertThat(p.subBatchId()).isNull();
        assertThat(p.transactionReference()).isNull();
        assertThat(p.totalPostBalance()).isNull();
        StreamPayload n = parse(with("productId", "null", "batchContext.batchId", "null"));
        assertThat(n.productId()).isNull();
        assertThat(n.batchId()).isNull();
    }

    @Test
    void parses_iso_instant_and_exact_decimal() {
        StreamPayload p = parse(with("amount.amount", "0.1000", "totalPostBalance", "12.50"));

        assertThat(p.created()).isEqualTo(Instant.parse("2033-09-02T12:00:00Z"));
        assertThat(p.amount()).isEqualTo(new BigDecimal("0.1000"));
        assertThat(p.amount().scale()).isEqualTo(4);
        assertThat(p.totalPostBalance()).isEqualTo(new BigDecimal("12.50"));
    }

    /**
     * Final-review M5: "created" is truncated to microseconds, Postgres's timestamptz precision (which
     * would round 0.4 µs before a month start into the next month).
     */
    @Test
    void created_is_truncated_to_microseconds_at_a_month_boundary() {
        StreamPayload p = parse(with("createdContext.created", "\"2034-01-31T23:59:59.9999996Z\""));

        assertThat(p.created()).isEqualTo(Instant.parse("2034-01-31T23:59:59.999999Z"));
        assertThat(java.time.YearMonth.from(p.created().atZone(java.time.ZoneOffset.UTC)))
                .isEqualTo(java.time.YearMonth.of(2034, 1));
        assertThat(parse(with("createdContext.created", "\"2034-01-31T23:59:59.123456789Z\"")).created())
                .isEqualTo(Instant.parse("2034-01-31T23:59:59.123456Z"));
    }

    @Test
    void created_parse_failures_name_the_field() {
        assertThatThrownBy(() -> parse(with("createdContext.created", "\"yesterday\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("created");
        assertThatThrownBy(() -> parse(with("createdContext.created", "12345")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("created");
    }

    @Test
    void money_within_numeric_20_4_is_accepted_at_the_edges() {
        assertThat(parse(with("amount.amount", "9999999999999999.9999")).amount())
                .isEqualTo(new BigDecimal("9999999999999999.9999"));
        assertThat(parse(with("amount.amount", "\"-9999999999999999.9999\"")).amount())
                .isEqualTo(new BigDecimal("-9999999999999999.9999"));
        assertThat(parse(with("amount.amount", "\"1.50000\"")).amount()).isEqualByComparingTo("1.5");
    }

    @Test
    void money_with_too_many_decimals_or_digits_is_rejected_naming_the_field() {
        assertThatThrownBy(() -> parse(with("amount.amount", "0.00001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount.amount", "\"1.23456\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount.amount", "10000000000000000")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("totalPostBalance", "\"-10000000000000000.0\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalPostBalance");
    }

    @Test
    void money_exponent_bombs_and_oversized_text_are_rejected_without_expanding() {
        assertThatThrownBy(() -> parse(with("amount.amount", "1e999999999")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount.amount", "\"1e999999999\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("amount.amount", "1e-999999999")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> parse(with("totalPostBalance", "\"" + "1".repeat(500) + "\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalPostBalance");
    }

    @Test
    void money_that_is_neither_number_nor_numeric_text_is_rejected() {
        for (String bad : new String[] {"true", "[]", "{}", "\"abc\"", "\"\""}) {
            assertThatThrownBy(() -> parse(with("amount.amount", bad)))
                    .as("amount=" + bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        }
    }

    @Test
    void text_fields_must_be_json_strings() {
        for (String bad : new String[] {"{}", "true", "5", "[]"}) {
            assertThatThrownBy(() -> parse(with("amount.currency", bad)))
                    .as("currency=" + bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
            assertThatThrownBy(() -> parse(with("id", bad)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
            assertThatThrownBy(() -> parse(with("type", bad)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        }
        for (String path : new String[] {
                "productId", "productType", "batchContext.batchId", "batchContext.subBatchId",
                "references.transactionReference"}) {
            assertThatThrownBy(() -> parse(with(path, "5")))
                    .as(path).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(path.substring(path.lastIndexOf('.') + 1));
        }
    }

    /**
     * F2a: the wire type code is checked at parse time. A code that would be stored as-is --
     * and could be rejected downstream -- must be poison (dead-lettered), never a retry-forever failure.
     * Every real code is upper-case letters, digits and underscores (source-schema.sql).
     */
    @Test
    void a_malformed_type_code_is_poison() {
        for (String bad : new String[] {"\"" + "A".repeat(65) + "\"", "\"tt_buy\"", "\"TT BUY\"", "\"\""}) {
            assertThatThrownBy(() -> parse(with("type", bad)))
                    .as("type=" + bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        }
        assertThat(parse(with("type", "\"" + "A".repeat(64) + "\"")).type()).hasSize(64);
        assertThat(parse(with("type", "\"TT_PRIZE_IN2\"")).type()).isEqualTo("TT_PRIZE_IN2");
    }

    /** A NUL in any text field would be unstorable in Postgres (text and jsonb alike). */
    @Test
    void a_nul_in_any_text_field_is_rejected_naming_the_field() {
        for (String path : new String[] {
                "id", "type", "productId", "productType", "amount.currency",
                "batchContext.batchId", "batchContext.subBatchId", "references.transactionReference",
                "description", "accountContext.accountType", "references.paymentId"}) {
            assertThatThrownBy(() -> parse(with(path, "\"ab\\u0000c\"")))
                    .as(path).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(path).hasMessageContaining("U+0000");
        }
        // ownerId: the NUL is rejected as text before the format is even looked at.
        assertThatThrownBy(() -> parse(with("userContext.ownerId", "\"1-7\\u0000\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userContext.ownerId").hasMessageContaining("U+0000");
    }

    @Test
    void the_raw_nul_handler_path_dead_letters_the_body_instead_of_delivering_it() {
        byte[] body = with("id", "\"tx\\u0000\"").getBytes(StandardCharsets.UTF_8);
        com.rabbitmq.stream.Message message = org.mockito.Mockito.mock(com.rabbitmq.stream.Message.class);
        org.mockito.Mockito.when(message.getBodyAsBinary()).thenReturn(body);
        java.util.List<Object> seen = new java.util.ArrayList<>();
        RabbitStreamSource.handleMessage(mapper, message, 3, new StreamSource.StreamHandler() {
            @Override
            public void onMessage(StreamPayload payload, long offset) {
                seen.add(payload);
            }

            @Override
            public void onUndeserializable(byte[] raw, long offset, Exception error) {
                seen.add(error.getMessage());
            }
        });
        assertThat(seen).singleElement().asString().contains("id").contains("U+0000");
    }

    // --- a valid base message built from raw JSON leaves, with path overrides ---

    @SuppressWarnings("unchecked")
    private static Map<String, Object> base() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "\"tx-1\"");
        m.put("type", "\"TT_BUY\"");
        m.put("amount", new LinkedHashMap<>(Map.of("amount", "1.0000", "currency", "\"EUR\"")));
        m.put("userContext", new LinkedHashMap<>(Map.of("ownerId", "\"1-7\"")));
        m.put("createdContext", new LinkedHashMap<>(Map.of("created", "\"2033-09-02T12:00:00Z\"")));
        m.put("productId", "\"p\"");
        m.put("productType", "\"instants\"");
        m.put("batchContext", new LinkedHashMap<>(Map.of("batchId", "\"b\"", "subBatchId", "\"s\"")));
        m.put("references", new LinkedHashMap<>(Map.of("transactionReference", "\"tx-1\"")));
        m.put("totalPostBalance", "5");
        return m;
    }

    /** The base message with (path, rawJson) pairs overridden; a path may create nested keys. */
    @SuppressWarnings("unchecked")
    private static String with(String... pathsAndValues) {
        Map<String, Object> m = base();
        for (int k = 0; k < pathsAndValues.length; k += 2) {
            Map<String, Object> at = m;
            String[] steps = pathsAndValues[k].split("\\.");
            for (int i = 0; i < steps.length - 1; i++) {
                Object next = at.get(steps[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    at.put(steps[i], next);
                }
                at = (Map<String, Object>) next;
            }
            at.put(steps[steps.length - 1], pathsAndValues[k + 1]);
        }
        return json(m);
    }

    @SuppressWarnings("unchecked")
    private static String without(String... paths) {
        Map<String, Object> m = base();
        for (String path : paths) {
            Map<String, Object> at = m;
            String[] steps = path.split("\\.");
            for (int i = 0; i < steps.length - 1; i++) {
                at = (Map<String, Object>) at.get(steps[i]);
            }
            at.remove(steps[steps.length - 1]);
        }
        return json(m);
    }

    @SuppressWarnings("unchecked")
    private static String json(Map<String, Object> m) {
        return m.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":"
                        + (e.getValue() instanceof Map<?, ?> nested ? json((Map<String, Object>) nested) : e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }
}
