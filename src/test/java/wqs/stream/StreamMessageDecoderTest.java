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
        assertThat(m.ownerId()).isEqualTo("1-1717");
        assertThat(m.created()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465648Z"));
        assertThat(m.transactionType()).isEqualTo("TT_TRANSFER_OUT");
        assertThat(m.amount()).isEqualByComparingTo("-12");
        assertThat(m.currency()).isEqualTo("EUR");
        assertThat(m.totalPostBalance()).isEqualByComparingTo("178");
        assertThat(m.transactionReference()).isEqualTo("5962");
        // Empty strings on the wire are absent values.
        assertThat(m.productId()).isNull();
        assertThat(m.productType()).isNull();
        assertThat(m.batchId()).isNull();
        assertThat(m.subBatchId()).isNull();
        assertThat(m.transactionSubtype()).isNull();
        assertThat(m.description()).isNull();
        assertThat(m.claimId()).isNull();
        assertThat(m.extTrxId()).isNull();
        // Every other stored field, under its column's name.
        assertThat(m.accountType()).isEqualTo("AT_MAIN");
        assertThat(m.accountNumber()).isEqualTo("0");
        assertThat(m.accountId()).isEqualTo("AT_MAIN-0");
        assertThat(m.batchEnded()).isFalse();
        assertThat(m.reservedAmount()).isEqualByComparingTo("0");
        assertThat(m.nonWithdrawableAmount()).isEqualByComparingTo("0");
        assertThat(m.monetary()).isTrue();
        assertThat(m.fromDescription()).isEqualTo("AT_MAIN");
        assertThat(m.toDescription()).isEqualTo("WITHDRAWAL*5208");
        assertThat(m.paymentId()).isEqualTo(6429L);
        assertThat(m.channel()).isEqualTo("WEB");
        assertThat(m.issued()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465648Z"));
    }

    @Test
    void decodes_the_captured_instants_prize() throws IOException {
        StreamMessage m = decoder.decode(fixture("instants-prize.json"));

        assertThat(m.id()).isEqualTo("5922");
        assertThat(m.ownerId()).isEqualTo("1-1945");
        assertThat(m.transactionType()).isEqualTo("PRIZE");
        assertThat(m.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(m.productId()).isEqualTo("TIKIKALACABANA");
        assertThat(m.productType()).isEqualTo("instants");
        assertThat(m.batchId()).isEqualTo("GS-ur5z5sgfr5de5flhjd6ycryf6u");
        assertThat(m.subBatchId()).isEqualTo("PS-62pflumf4bcz7jdtfc3wl5yqfm");
        assertThat(m.batchEnded()).isTrue();
        assertThat(m.description()).isEqualTo("Payout Play Step");
        assertThat(m.extTrxId()).isEqualTo("PS-62pflumf4bcz7jdtfc3wl5yqfm-1-prize");
        assertThat(m.extSystemId()).isEqualTo("PDS");
        assertThat(m.paymentId()).isNull();
    }

    @Test
    void monetary_defaults_to_true_when_absent() throws IOException {
        assertThat(decode(n -> ((ObjectNode) n).remove("monetary")).monetary()).isTrue();
        assertThat(decode(n -> ((ObjectNode) n).put("monetary", false)).monetary()).isFalse();
    }

    @Test
    void reads_a_wallet_id_sent_as_a_number() throws IOException {
        assertThat(decode(n -> ((ObjectNode) n.get("references")).put("claimId", 77)).claimId()).isEqualTo(77L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12a", "-5", "1.5", "99999999999999999999"})
    void rejects_a_wallet_id_that_is_not_a_whole_number(String paymentId) {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("references")).put("paymentId", paymentId)))
                .isInstanceOf(DecodeException.class).hasMessage("references.paymentId must be a whole number");
    }

    @Test
    void rejects_a_value_longer_than_its_column() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n).put("toDescription", "x".repeat(129))))
                .isInstanceOf(DecodeException.class).hasMessage("toDescription is longer than 128 characters");
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("accountContext")).put("accountType", "x".repeat(33))))
                .isInstanceOf(DecodeException.class).hasMessage("accountContext.accountType is longer than 32 characters");
    }

    @Test
    void rejects_a_message_without_account_or_issue_time() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n).remove("accountContext")))
                .isInstanceOf(DecodeException.class).hasMessage("accountContext is missing");
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n).put("account", "")))
                .isInstanceOf(DecodeException.class).hasMessage("account is missing or blank");
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("createdContext")).remove("issued")))
                .isInstanceOf(DecodeException.class).hasMessage("createdContext.issued is missing or blank");
    }

    @Test
    void rejects_a_non_boolean_flag() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("batchContext")).put("batchEnded", "yes")))
                .isInstanceOf(DecodeException.class).hasMessage("batchContext.batchEnded must be true or false");
    }

    @Test
    void truncates_the_instant_to_microseconds() throws IOException {
        StreamMessage m = decode(n -> ((ObjectNode) n.get("createdContext")).put("created", "2026-10-31T23:59:59.9999996Z"));
        assertThat(m.created()).isEqualTo(Instant.parse("2026-10-31T23:59:59.999999Z"));
    }

    @Test
    void another_owner_type_is_another_owner() throws IOException {
        StreamMessage m = decode(n -> ((ObjectNode) n.get("userContext")).put("ownerId", "2-1717"));
        assertThat(m.ownerId()).isEqualTo("2-1717");
    }

    @Test
    void the_owner_id_gets_one_canonical_spelling() throws IOException {
        StreamMessage m = decode(n -> ((ObjectNode) n.get("userContext")).put("ownerId", "01-001717"));
        assertThat(m.ownerId()).isEqualTo("1-1717");
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
    void rejects_an_amount_with_more_than_three_decimals() {
        assertThatThrownBy(() -> decode(n -> ((ObjectNode) n.get("amount")).put("amount", new BigDecimal("1.2345"))))
                .isInstanceOf(DecodeException.class).hasMessage("amount.amount does not fit numeric(15,3)");
    }

    @Test
    void rejects_an_amount_with_a_huge_exponent() throws IOException {
        byte[] body = new String(fixture("transfer-out.json"), java.nio.charset.StandardCharsets.UTF_8).replace("\"amount\": -12", "\"amount\": 1E+2147483647")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> decoder.decode(body))
                .isInstanceOf(DecodeException.class).hasMessage("amount.amount does not fit numeric(15,3)");
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
    void rejects_a_nul_character_in_a_text_field() {
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
