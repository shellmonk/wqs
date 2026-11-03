package wqs.ingest.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.ingest.StrictJson;
import wqs.projection.source.DeltaRow;

/** Golden fixtures for the /wallet/delta row decoder (plan D1-D4). */
class DeltaRowDecoderTest extends PostgresTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeltaRowDecoder decoder = new DeltaRowDecoder(defaults());

    static DeltaProperties defaults() {
        return new DeltaProperties(null, null, 0, null, 0, null, null, null);
    }

    @Test
    void payment_out_maps_per_D3_and_detail_is_the_D4_allowlist() {
        DeltaRow row = decode("payment");
        ProjectionTransaction transaction = row.transaction();

        assertThat(row.source()).isEqualTo(SourceKind.PAYMENT);
        assertThat(row.ownerType()).isEqualTo(1);
        assertThat(row.userId()).isEqualTo(123456L);
        assertThat(row.version()).isEqualTo(Instant.parse("2026-10-30T09:20:00Z"));
        assertThat(transaction.source()).isEqualTo(SourceKind.PAYMENT);
        assertThat(transaction.sourceId()).isEqualTo("900001");
        assertThat(transaction.userId()).isEqualTo(123456L);
        assertThat(transaction.occurredAt()).isEqualTo(Instant.parse("2026-10-30T09:15:00Z"));
        assertThat(transaction.amount()).isEqualByComparingTo("-25.500");     // paymentInOut 2 = OUT
        assertThat(transaction.currency()).isEqualTo("EUR");
        assertThat(transaction.type()).isEqualTo("WITHDRAWAL");
        assertThat(transaction.productId()).isNull();
        assertThat(transaction.productType()).isNull();
        assertThat(transaction.batchId()).isNull();
        assertThat(transaction.subBatchId()).isNull();
        assertThat(transaction.cancels()).isNull();
        assertThat(transaction.balanceAfter()).isNull();
        // subChannel "" is dropped; card, bank, email, xml and json data never reach detail.
        assertThat(detail(transaction)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("paymentType", "WITHDRAWAL"),
                Map.entry("paymentInOut", "2"),
                Map.entry("status", "3"),
                Map.entry("statusDate", "2026-10-30T09:20:00Z"),
                Map.entry("channel", "WEB"),
                Map.entry("description", "Bank withdrawal"),
                Map.entry("grossAmount", "26.000"),
                Map.entry("serviceCost", "0.500"),
                Map.entry("workflowStatus", "IN_REVIEW"),
                Map.entry("reviewHandling", "NONE"),
                Map.entry("approved", "2026-10-30T09:18:00Z")));
        assertThat(transaction.detailJson()).doesNotContain("ENC:", "payCardNumber", "payEmail", "xmlData", "acme");
    }

    @Test
    void payment_in_is_positive() {
        ObjectNode json = fixture("payment");
        json.put("paymentInOut", 1);
        assertThat(decoder.decode(json).transaction().amount()).isEqualByComparingTo("25.500");
    }

    @Test
    void payment_direction_other_than_in_or_out_is_poison() {
        ObjectNode json = fixture("payment");
        json.put("paymentInOut", 3);
        assertThatThrownBy(() -> decoder.decode(json))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paymentInOut");
    }

    @Test
    void claimable_prize_maps_per_D3_with_synthetic_type_and_product_and_batch_fields() {
        DeltaRow row = decode("claimable-prize");
        ProjectionTransaction transaction = row.transaction();

        assertThat(row.source()).isEqualTo(SourceKind.CLAIMABLE_PRIZE);
        assertThat(row.ownerType()).isEqualTo(1);       // playerId: a player by definition
        assertThat(row.userId()).isEqualTo(123456L);
        assertThat(row.version()).isEqualTo(Instant.parse("2026-10-29T18:05:00Z"));
        assertThat(transaction.sourceId()).isEqualTo("700001");
        assertThat(transaction.occurredAt()).isEqualTo(Instant.parse("2026-10-29T18:00:00Z"));
        assertThat(transaction.amount()).isEqualByComparingTo("150.000");
        assertThat(transaction.currency()).isEqualTo("EUR");
        assertThat(transaction.type()).isEqualTo("CLAIMABLE_PRIZE");
        assertThat(transaction.productId()).isEqualTo("LOTTO6");
        assertThat(transaction.productType()).isEqualTo("lotto");
        assertThat(transaction.batchId()).isEqualTo("5f0e8a2c-1b3d-4e6f-9a7b-0c1d2e3f4a5b");
        assertThat(transaction.subBatchId()).isNull();          // "" -> null
        assertThat(transaction.cancels()).isNull();
        // workflowStatus "" is dropped; account and xml data never reach detail.
        assertThat(detail(transaction)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "UNCLAIMED",
                "prizeLevel", "3",
                "wagerAmount", "2.000",
                "onHold", "false",
                "claimExpiryTimestamp", "2026-11-28T18:00:00Z"));
        assertThat(transaction.detailJson()).doesNotContain("10001234", "xmlData", "T-1");
    }

    @Test
    void manual_adjustment_withdrawal_maps_per_D3() {
        DeltaRow row = decode("manual-adjustment");
        ProjectionTransaction transaction = row.transaction();

        assertThat(row.source()).isEqualTo(SourceKind.MANUAL_ADJUSTMENT);
        assertThat(row.ownerType()).isEqualTo(1);
        assertThat(row.userId()).isEqualTo(123456L);
        assertThat(transaction.sourceId()).isEqualTo("500001");
        // No "created" column: the request's "issued" is its creation time.
        assertThat(transaction.occurredAt()).isEqualTo(Instant.parse("2026-10-28T12:00:00Z"));
        assertThat(transaction.amount()).isEqualByComparingTo("-10.000");     // WITHDRAWAL
        assertThat(transaction.currency()).isEqualTo("EUR");
        assertThat(transaction.type()).isEqualTo("TT_MANUAL_ADJUSTMENT");
        assertThat(transaction.productId()).isNull();
        assertThat(transaction.batchId()).isNull();
        assertThat(detail(transaction)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "operation", "WITHDRAWAL",
                "transactionType", "TT_MANUAL_ADJUSTMENT",
                "transactionSubtype", "CORRECTION",
                "status", "APPROVED",
                "statusDate", "2026-10-28T14:30:00Z",
                "reason", "Duplicate deposit reversed",
                "approved", "2026-10-28T14:30:00Z"));
        assertThat(transaction.detailJson()).doesNotContain("10001234");
    }

    @Test
    void manual_adjustment_deposit_is_positive() {
        ObjectNode json = fixture("manual-adjustment");
        json.put("operation", "DEPOSIT");
        assertThat(decoder.decode(json).transaction().amount()).isEqualByComparingTo("10.000");
    }

    @Test
    void adjustment_version_is_statusDate() {
        ObjectNode json = fixture("manual-adjustment");
        json.put("updated", "2030-01-01T00:00:00Z");   // not a column of the table: ignored
        assertThat(decoder.decode(json).version()).isEqualTo(Instant.parse("2026-10-28T14:30:00Z"));
    }

    @Test
    void limit_with_no_amount_or_currency_maps_to_zero_and_XXX() {
        DeltaRow row = decode("limit");
        ProjectionTransaction transaction = row.transaction();

        assertThat(row.source()).isEqualTo(SourceKind.LIMIT);
        assertThat(row.ownerType()).isEqualTo(1);
        assertThat(row.version()).isEqualTo(Instant.parse("2026-10-27T08:00:05Z"));
        assertThat(transaction.sourceId()).isEqualTo("300001");
        assertThat(transaction.occurredAt()).isEqualTo(Instant.parse("2026-10-27T08:00:00Z"));
        assertThat(transaction.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(transaction.currency()).isEqualTo("XXX");
        assertThat(transaction.type()).isEqualTo("PLAY_COUNT");
        // gameGroup "" is dropped.
        assertThat(detail(transaction)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "quantityType", "COUNT",
                "limitType", "PLAY_COUNT",
                "period", "DAILY",
                "count", "5",
                "effectiveFrom", "2026-10-28T00:00:00Z",
                "approved", "2026-10-27T08:00:05Z",
                "reason", "Player request"));
    }

    @Test
    void a_page_mixes_all_four_sources() throws IOException {
        JsonNode page = read("page");
        List<SourceKind> sources = new ArrayList<>();
        page.forEach(row -> sources.add(decoder.decode(row).source()));
        assertThat(sources).containsExactly(
                SourceKind.PAYMENT, SourceKind.CLAIMABLE_PRIZE, SourceKind.MANUAL_ADJUSTMENT, SourceKind.LIMIT);
    }

    @Test
    void source_matching_is_case_insensitive() {
        ObjectNode json = fixture("payment");
        json.put("source", "WALPAYMENTS");
        assertThat(decoder.decode(json).source()).isEqualTo(SourceKind.PAYMENT);
    }

    @Test
    void unknown_source_is_poison() {
        ObjectNode json = fixture("payment");
        json.put("source", "walAccountTransactions");
        assertThatThrownBy(() -> decoder.decode(json))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        json.remove("source");
        assertThatThrownBy(() -> decoder.decode(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_player_owner_decodes_with_its_type() {
        ObjectNode payment = fixture("payment");
        payment.put("ownerType", 2);
        DeltaRow row = decoder.decode(payment);
        assertThat(row.ownerType()).isEqualTo(2);
        assertThat(row.userId()).isEqualTo(123456L);

        ObjectNode limit = fixture("limit");
        limit.put("userType", 3);
        assertThat(decoder.decode(limit).ownerType()).isEqualTo(3);
    }

    @Test
    void malformed_owner_is_poison() {
        for (String bad : new String[] {"", "-1", "12a", " 1", "1.5"}) {
            ObjectNode json = fixture("payment");
            json.put("ownerId", bad);
            assertThatThrownBy(() -> decoder.decode(json))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");
        }
        ObjectNode type = fixture("payment");
        type.put("ownerType", "one");
        assertThatThrownBy(() -> decoder.decode(type)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode prize = fixture("claimable-prize");
        prize.put("playerId", "p-1");
        assertThatThrownBy(() -> decoder.decode(prize))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("playerId");
    }

    @Test
    void missing_required_field_is_poison_naming_it() {
        for (String[] c : new String[][] {
                {"payment", "paymentId"}, {"payment", "created"}, {"payment", "updated"}, {"payment", "amount"},
                {"claimable-prize", "prizeAmount"}, {"claimable-prize", "id"},
                {"manual-adjustment", "statusDate"}, {"manual-adjustment", "issued"},
                {"manual-adjustment", "operation"}, {"limit", "limitType"}, {"limit", "userId"}}) {
            ObjectNode json = fixture(c[0]);
            json.putNull(c[1]);
            assertThatThrownBy(() -> decoder.decode(json))
                    .as(c[0] + "." + c[1]).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(c[1]);
        }
    }

    @Test
    void bad_number_or_timestamp_is_poison() {
        ObjectNode amount = fixture("payment");
        amount.put("amount", "lots");
        assertThatThrownBy(() -> decoder.decode(amount)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode tooBig = fixture("claimable-prize");
        tooBig.put("prizeAmount", new BigDecimal("1e30"));
        assertThatThrownBy(() -> decoder.decode(tooBig)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode when = fixture("limit");
        when.put("updated", "yesterday");
        assertThatThrownBy(() -> decoder.decode(when))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("updated");
        ObjectNode id = fixture("limit");
        id.put("id", 1.5);
        assertThatThrownBy(() -> decoder.decode(id)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode type = fixture("limit");
        type.put("limitType", "play count");
        assertThatThrownBy(() -> decoder.decode(type)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Ruling R-a: adjustment requests have no "created"; "issued" is the occurredAt. */
    @Test
    void adjustment_occurredAt_is_issued() {
        ObjectNode json = fixture("manual-adjustment");
        json.put("created", "2030-01-01T00:00:00Z");   // not a column of the table: ignored
        assertThat(decoder.decode(json).transaction().occurredAt()).isEqualTo(Instant.parse("2026-10-28T12:00:00Z"));
    }

    /** Ruling R-b: a null or blank paymentType is the synthetic PAYMENT type, not poison. */
    @Test
    void null_or_blank_payment_type_is_PAYMENT() {
        ObjectNode none = fixture("payment");
        none.putNull("paymentType");
        assertThat(decoder.decode(none).transaction().type()).isEqualTo("PAYMENT");
        ObjectNode blank = fixture("payment");
        blank.put("paymentType", "  ");
        assertThat(decoder.decode(blank).transaction().type()).isEqualTo("PAYMENT");
        ObjectNode absent = fixture("payment");
        absent.remove("paymentType");
        assertThat(decoder.decode(absent).transaction().type()).isEqualTo("PAYMENT");
    }

    /** Ruling R-c: zoneless source timestamps are UTC; zoned ones keep working; garbage is poison. */
    @Test
    void zoneless_timestamps_are_utc() {
        Instant expected = Instant.parse("2026-10-29T11:22:04.465648Z");
        ObjectNode t = fixture("claimable-prize");
        t.put("created", "2026-10-29T11:22:04.465648");
        t.put("updated", "2026-10-29 11:22:04.465");
        DeltaRow row = decoder.decode(t);
        assertThat(row.transaction().occurredAt()).isEqualTo(expected);
        assertThat(row.version()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465Z"));

        ObjectNode offset = fixture("claimable-prize");
        offset.put("created", "2026-10-29T13:22:04.465648+02:00");
        assertThat(decoder.decode(offset).transaction().occurredAt()).isEqualTo(expected);

        for (String bad : new String[] {"2026-10-29", "2026-10-29T25:00:00", "29/10/2026 11:22", "2026-10-29X11:22:04"}) {
            ObjectNode g = fixture("claimable-prize");
            g.put("created", bad);
            assertThatThrownBy(() -> decoder.decode(g))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("created");
        }
    }

    /** Sub-microsecond wire values are truncated to timestamptz precision (occurredAt and version). */
    @Test
    void timestamps_are_truncated_to_microseconds() {
        ObjectNode t = fixture("limit");
        t.put("created", "2026-10-29T11:22:04.465648999Z");
        t.put("updated", "2026-10-29T11:22:04.465648501");
        DeltaRow row = decoder.decode(t);
        assertThat(row.transaction().occurredAt()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465648Z"));
        assertThat(row.version()).isEqualTo(Instant.parse("2026-10-29T11:22:04.465648Z"));
    }

    @Test
    void a_row_that_is_not_an_object_is_poison() {
        assertThatThrownBy(() -> decoder.decode(MAPPER.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void properties_default_per_D1_D2_D7() {
        DeltaProperties p = defaults();
        assertThat(p.cursorParam()).isEqualTo("from");
        assertThat(p.pageLimit()).isEqualTo(1000);
        assertThat(p.overlap()).isEqualTo(Duration.ofSeconds(120));
        assertThat(p.maxPagesPerRun()).isEqualTo(20);
        assertThat(p.sourceOf("walPayments")).contains(SourceKind.PAYMENT);
        assertThat(p.sourceOf("walclaimableprizes")).contains(SourceKind.CLAIMABLE_PRIZE);
        assertThat(p.sourceOf("walManualAdjustmentRequests")).contains(SourceKind.MANUAL_ADJUSTMENT);
        assertThat(p.sourceOf("walLimits")).contains(SourceKind.LIMIT);
        assertThat(p.sourceOf("walAccounts")).isEmpty();
    }

    private DeltaRow decode(String name) {
        return decoder.decode(fixture(name));
    }

    private static ObjectNode fixture(String name) {
        try {
            return (ObjectNode) read(name);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String name) throws IOException {
        try (InputStream in = DeltaRowDecoderTest.class.getResourceAsStream("/delta/" + name + ".json")) {
            return StrictJson.reader(MAPPER).readTree(in);
        }
    }

    private static Map<String, String> detail(ProjectionTransaction transaction) {
        try {
            return MAPPER.readValue(transaction.detailJson(), new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
