package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wqs.db.TxTypeRegistry;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.projection.source.StreamPayload;

class StreamPayloadMapperTest {

    private static final Instant WHEN = Instant.parse("2033-07-05T09:00:00Z");

    private final StreamPayloadMapper mapper = new StreamPayloadMapperImpl();
    private final TxTypeRegistry registry = mock(TxTypeRegistry.class);

    private static StreamPayload payload(String id, String type, String reference, Map<String, String> detail) {
        return new StreamPayload(
                id, 1, 7L, WHEN, type, "TIKI", "instants", "b-1", "p-1",
                new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"), reference, detail);
    }

    @Test
    void maps_structural_fields_and_resolves_the_type_through_the_registry() {
        when(registry.idOf("TT_BUY")).thenReturn((short) 42);

        ProjectionItem item = mapper.toItem(payload("tx-1", "TT_BUY", "tx-1", Map.of()), registry);

        assertThat(item.sourceId()).isEqualTo("tx-1");
        assertThat(item.userId()).isEqualTo(7L);
        assertThat(item.source()).isEqualTo(SourceKind.TRANSACTION);
        assertThat(item.occurredAt()).isEqualTo(WHEN);
        assertThat(item.productId()).isEqualTo("TIKI");
        assertThat(item.productType()).isEqualTo("instants");
        assertThat(item.batchId()).isEqualTo("b-1");
        assertThat(item.subBatchId()).isEqualTo("p-1");
        assertThat(item.type()).as("the registry id, not the wire text").isEqualTo((short) 42);
        assertThat(item.amount()).isEqualByComparingTo("5.0000");
        assertThat(item.currency()).isEqualTo("EUR");
        assertThat(item.balanceAfter()).isEqualByComparingTo("95.0000");
        assertThat(item.cancels()).isNull();
        assertThat(item.detailJson()).isNull();
    }

    @Test
    void detail_becomes_detail_json() {
        when(registry.idOf("TT_BUY")).thenReturn((short) 1);

        ProjectionItem item = mapper.toItem(
                payload("tx-2", "TT_BUY", "tx-2", Map.of("toDescription", "WITHDRAWAL*5208")), registry);

        assertThat(item.detailJson()).contains("\"toDescription\":\"WITHDRAWAL*5208\"");
    }

    @Test
    void cancel_type_with_foreign_reference_cancels() {
        when(registry.idOf("TT_CANCEL")).thenReturn((short) 9);

        ProjectionItem item = mapper.toItem(payload("200", "TT_CANCEL", "100", Map.of()), registry);

        assertThat(item.isCancellation()).isTrue();
        assertThat(item.cancels()).isEqualTo("100");
    }

    @Test
    void every_cancel_type_cancels() {
        for (String type : new String[] {"TT_CANCEL", "CANCEL", "TT_TOMBSTONE"}) {
            when(registry.idOf(type)).thenReturn((short) 9);
            assertThat(mapper.toItem(payload("200", type, "100", Map.of()), registry).cancels())
                    .as(type).isEqualTo("100");
        }
    }

    @Test
    void cancel_type_self_reference_is_not_a_cancel() {
        when(registry.idOf("TT_CANCEL")).thenReturn((short) 9);

        assertThat(mapper.toItem(payload("200", "TT_CANCEL", "200", Map.of()), registry).cancels()).isNull();
    }

    @Test
    void cancel_type_without_reference_is_not_a_cancel() {
        when(registry.idOf("TT_CANCEL")).thenReturn((short) 9);

        assertThat(mapper.toItem(payload("200", "TT_CANCEL", null, Map.of()), registry).cancels()).isNull();
        assertThat(mapper.toItem(payload("200", "TT_CANCEL", "", Map.of()), registry).cancels()).isNull();
    }

    @Test
    void undo_and_rollback_types_are_not_cancels() {
        for (String type : new String[] {"TT_BUY_UNDO", "BUY_ROLLBACK", "TT_PRIZE_IN", "PRIZE"}) {
            when(registry.idOf(type)).thenReturn((short) 9);
            assertThat(mapper.toItem(payload("200", type, "100", Map.of()), registry).cancels())
                    .as(type).isNull();
        }
    }

    @Test
    void undo_type_is_not_a_cancel() {
        when(registry.idOf("TT_CANCEL_UNDO")).thenReturn((short) 9);

        assertThat(mapper.toItem(payload("200", "TT_CANCEL_UNDO", "100", Map.of()), registry).cancels()).isNull();
    }

    @Test
    void a_non_player_owner_still_maps() {
        when(registry.idOf("TT_BUY")).thenReturn((short) 1);
        StreamPayload operator = new StreamPayload(
                "tx-9", 2, 7L, WHEN, "TT_BUY", null, null, null, null,
                BigDecimal.ONE, "EUR", null, "tx-9", Map.of());

        assertThat(mapper.toItem(operator, registry).userId()).isEqualTo(7L);
    }
}
