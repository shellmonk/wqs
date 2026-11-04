package wqs.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wqs.dto.StreamMessage;
import wqs.exception.DecodeException;

/** Golden fixtures are the two captured messages in docs/reference/stream-message-samples.md. */
class StreamMessageDecoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StreamMessageDecoder decoder = new StreamMessageDecoder(mapper);

    @Test
    void decodes_the_captured_transfer_out() throws IOException {
        StreamMessage m = decoder.decode(fixture("transfer-out.json"));

        assertThat(m.id()).isEqualTo("5962");
        assertThat(m.ownerType()).isEqualTo((short) 1);
        assertThat(m.userId()).isEqualTo(1717L);
        assertThat(m.created()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465648Z"));
        assertThat(m.type()).isEqualTo("TT_TRANSFER_OUT");
        assertThat(m.amount()).isEqualByComparingTo("-12");
        assertThat(m.currency()).isEqualTo("EUR");
        assertThat(m.totalPostBalance()).isEqualByComparingTo("178");
        assertThat(m.transactionReference()).isEqualTo("5962");
        // Empty strings on the wire are absent values.
        assertThat(m.productId()).isNull();
        assertThat(m.productType()).isNull();
        assertThat(m.batchId()).isNull();
        assertThat(m.subBatchId()).isNull();
        assertThat(m.detail()).containsEntry("references.paymentId", "6429")
                .containsEntry("createdContext.channel", "WEB")
                .containsEntry("monetary", "true")
                .doesNotContainKey("subtype");
    }

    @Test
    void decodes_the_captured_instants_prize() throws IOException {
        StreamMessage m = decoder.decode(fixture("instants-prize.json"));

        assertThat(m.id()).isEqualTo("5922");
        assertThat(m.userId()).isEqualTo(1945L);
        assertThat(m.type()).isEqualTo("PRIZE");
        assertThat(m.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(m.productId()).isEqualTo("TIKIKALACABANA");
        assertThat(m.productType()).isEqualTo("instants");
        assertThat(m.batchId()).isEqualTo("GS-ur5z5sgfr5de5flhjd6ycryf6u");
        assertThat(m.subBatchId()).isEqualTo("PS-62pflumf4bcz7jdtfc3wl5yqfm");
        assertThat(m.detail()).containsEntry("batchContext.batchEnded", "true")
                .containsEntry("references.extSystemId", "PDS");
    }

    @Test
    void detail_never_carries_fields_outside_the_allowlist() throws IOException {
        StreamMessage m = decoder.decode(fixture("transfer-out.json"));
        // toDescription carries masked card digits ("WITHDRAWAL*5208").
        assertThat(m.detail().keySet()).allMatch(StreamMessageDecoder.DETAIL_ALLOWLIST::contains);
        assertThat(m.detail().values()).noneMatch(v -> v.contains("5208"));
    }

    @Test
    void truncates_the_instant_to_microseconds() throws IOException {
        StreamMessage m = decode(n -> ((ObjectNode) n.get("createdContext")).put("created", "2026-10-31T23:59:59.9999996Z"));
        assertThat(m.created()).isEqualTo(Instant.parse("2026-10-31T23:59:59.999999Z"));
    }

    @Test
    void a_non_player_owner_keeps_its_owner_type() throws IOException {
        StreamMessage m = decode(n -> ((ObjectNode) n.get("userContext")).put("ownerId", "2-1717"));
        assertThat(m.ownerType()).isEqualTo((short) 2);
        assertThat(m.userId()).isEqualTo(1717L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1717", "1-", "-1717", "a-1717", "1-17x17", "99999-1"})
    void rejects_a_malformed_owner_id_without_quoting_it(String ownerId) {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("userContext")).put("ownerId", ownerId)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("userContext.ownerId")
                .hasMessageNotContaining(ownerId);
    }

    @Test
    void rejects_a_missing_id() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n).remove("id")))
                .isInstanceOf(DecodeException.class).hasMessage("id is missing or blank");
    }

    @Test
    void rejects_a_string_amount() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("amount")).put("amount", "12")))
                .isInstanceOf(DecodeException.class).hasMessage("amount.amount must be a number");
    }

    @Test
    void rejects_an_amount_with_more_than_four_decimals() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("amount")).put("amount", new BigDecimal("1.23456"))))
                .isInstanceOf(DecodeException.class).hasMessage("amount.amount does not fit numeric(20,4)");
    }

    @Test
    void rejects_an_amount_with_a_huge_exponent() throws IOException {
        byte[] body = new String(fixture("transfer-out.json"), java.nio.charset.StandardCharsets.UTF_8).replace("\"amount\": -12", "\"amount\": 1E+2147483647")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> decoder.decode(body))
                .isInstanceOf(DecodeException.class).hasMessage("amount.amount does not fit numeric(20,4)");
    }

    @Test
    void rejects_a_bad_currency() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("amount")).put("currency", "eur")))
                .isInstanceOf(DecodeException.class).hasMessageContaining("amount.currency");
    }

    @Test
    void rejects_a_bad_instant() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("createdContext")).put("created", "2026-10-29 11:22")))
                .isInstanceOf(DecodeException.class).hasMessage("createdContext.created must be an ISO-8601 instant");
    }

    @Test
    void rejects_invalid_json_without_quoting_it() {
        byte[] body = "{\"id\": \"SECRET".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> decoder.decode(body))
                .isInstanceOf(DecodeException.class).hasMessage("not valid JSON");
    }

    @Test
    void rejects_a_duplicated_key() {
        byte[] body = "{\"id\": \"1\", \"id\": \"2\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> decoder.decode(body))
                .isInstanceOf(DecodeException.class).hasMessage("not valid JSON");
    }

    @Test
    void rejects_a_nul_character_in_a_column_field() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n).put("productId", "A\u0000B")))
                .isInstanceOf(DecodeException.class).hasMessage("productId contains a NUL character");
    }

    @Test
    void rejects_a_nul_character_in_a_detail_field() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n).put("description", "A\u0000B")))
                .isInstanceOf(DecodeException.class).hasMessage("description contains a NUL character");
    }

    @Test
    void rejects_a_json_array() {
        assertThatThrownBy(() -> decoder.decode("[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DecodeException.class).hasMessage("not a JSON object");
    }

    private StreamMessage decode(Consumer<com.fasterxml.jackson.databind.JsonNode> edit) throws IOException {
        var node = mapper.readTree(fixture("transfer-out.json"));
        edit.accept(node);
        return decoder.decode(mapper.writeValueAsBytes(node));
    }

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = StreamMessageDecoderTest.class.getResourceAsStream("/stream/" + name)) {
            return in.readAllBytes();
        }
    }
}
