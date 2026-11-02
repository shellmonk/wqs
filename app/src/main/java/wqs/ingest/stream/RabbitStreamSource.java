package wqs.ingest.stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Real stream subscription. Offset tracking is NOT delegated to the broker -- the offset is
 * persisted by the projection transaction instead (spec 6.2), so this only chooses where to
 * start reading.
 *
 * <p>Deliberately not a Spring component: it needs a {@link Environment}, and production wiring
 * of that bean and the consumer lifecycle is a separate concern.
 *
 * <p>O-13 assumption: wire field names equal the {@link StreamPayload} record-component names.
 * Every other field is preserved in {@code extra} (as text) so it reaches the "detail" jsonb.
 * Parsing goes through a JSON tree because binding straight to the record would silently drop
 * unknown fields.
 */
public class RabbitStreamSource implements StreamSource {

    private static final Set<String> KNOWN = Set.of(
            "transactionId", "userId", "occurredAt", "productId", "batchId", "subBatchId",
            "type", "amount", "currency", "balanceAfter", "cancelsTransactionId");

    private final Environment environment;
    private final StreamProperties properties;
    private final ObjectMapper objectMapper;
    private com.rabbitmq.stream.Consumer consumer;

    public RabbitStreamSource(
            Environment environment, StreamProperties properties, ObjectMapper objectMapper) {
        this.environment = environment;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void subscribe(long fromOffset, StreamHandler handler) {
        consumer = environment.consumerBuilder()
                .stream(properties.name())
                .offset(OffsetSpecification.offset(fromOffset))
                .messageHandler((context, message) -> {
                    StreamPayload payload = parse(objectMapper, message.getBodyAsBinary());
                    handler.onMessage(payload, context.offset());
                })
                .build();
    }

    @Override
    public void close() {
        if (consumer != null) {
            consumer.close();
        }
    }

    static StreamPayload parse(ObjectMapper objectMapper, byte[] body) {
        JsonNode root;
        try {
            // BigDecimal floats with trailing zeros kept: "0.1000" stays scale 4, not double / 0.1.
            root = objectMapper.reader()
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                    .readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("undeserializable stream message", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("stream message is not a JSON object");
        }

        Map<String, String> extra = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!KNOWN.contains(entry.getKey()) && !value.isNull()) {
                extra.put(entry.getKey(), value.isContainerNode() ? value.toString() : value.asText());
            }
        });

        return new StreamPayload(
                required(root, "transactionId").asText(),
                required(root, "userId").asLong(),
                Instant.parse(required(root, "occurredAt").asText()),
                optional(root, "productId") == null ? null : root.get("productId").asInt(),
                text(root, "batchId"),
                text(root, "subBatchId"),
                (short) required(root, "type").asInt(),
                decimal(required(root, "amount")),
                required(root, "currency").asText(),
                optional(root, "balanceAfter") == null ? null : decimal(root.get("balanceAfter")),
                text(root, "cancelsTransactionId"),
                extra);
    }

    private static JsonNode optional(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node;
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            throw new IllegalArgumentException("stream message is missing required field: " + field);
        }
        return node;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        return node == null ? null : node.asText();
    }

    /** Exact decimal: numbers via their decimal value, strings via their text. Never double. */
    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
    }
}
