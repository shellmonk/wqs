package wqs.ingest.stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import java.nio.charset.StandardCharsets;
import com.rabbitmq.stream.OffsetSpecification;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Real stream subscription. Offset tracking is NOT delegated to the broker -- the offset is
 * persisted by the projection transaction instead (spec 6.2), so this only chooses where to
 * start reading.
 *
 * <p>Not a Spring component itself: {@link StreamConfig} builds it (only when the stream is enabled).
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
        // Never leave a previous consumer alive beside the new one. StreamConsumer has already
        // closed this source outside its monitor, so this close() is a no-op by construction.
        close();
        consumer = environment.consumerBuilder()
                .stream(properties.name())
                .offset(OffsetSpecification.offset(fromOffset))
                .messageHandler((context, message) ->
                        handleMessage(objectMapper, message, context.offset(), handler))
                .build();
    }

    /**
     * Body extraction is inside the try on purpose: for AmqpValue/AmqpSequence bodies (AMQP 1.0,
     * .NET, Go producers) the client's getBodyAsBinary() throws ClassCastException, and an
     * exception on the client thread skips the message silently. Only extraction/parse failures
     * are poison; handler failures must not be caught here.
     */
    static void handleMessage(ObjectMapper objectMapper, Message message, long offset, StreamHandler handler) {
        byte[] body = null;
        StreamPayload payload;
        try {
            body = message.getBodyAsBinary();
            payload = parse(objectMapper, body);
        } catch (Exception e) {
            handler.onUndeserializable(body != null ? body : bestEffortBody(message), offset, e);
            return;
        }
        handler.onMessage(payload, offset);
    }

    private static byte[] bestEffortBody(Message message) {
        try {
            Object raw = message.getBody();
            return raw == null ? new byte[0] : String.valueOf(raw).getBytes(StandardCharsets.UTF_8);
        } catch (Exception | LinkageError e) {
            return new byte[0];
        }
    }

    @Override
    public void close() {
        if (consumer != null) {
            com.rabbitmq.stream.Consumer old = consumer;
            consumer = null;
            old.close();
        }
    }

    /** Cap on raw numeric text before it is handed to a parser (numeric(20,4) needs < 30 chars). */
    private static final int MAX_NUMERIC_TEXT = 64;
    private static final int MONEY_SCALE = 4;
    private static final int MONEY_INTEGER_DIGITS = 16; // numeric(20,4)

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
                textual(required(root, "transactionId"), "transactionId"),
                (long) integral(required(root, "userId"), "userId", Long.MIN_VALUE, Long.MAX_VALUE),
                instant(required(root, "occurredAt")),
                optional(root, "productId") == null ? null
                        : (int) integral(root.get("productId"), "productId", Integer.MIN_VALUE, Integer.MAX_VALUE),
                text(root, "batchId"),
                text(root, "subBatchId"),
                (short) integral(required(root, "type"), "type", Short.MIN_VALUE, Short.MAX_VALUE),
                money(required(root, "amount"), "amount"),
                textual(required(root, "currency"), "currency"),
                optional(root, "balanceAfter") == null ? null : money(root.get("balanceAfter"), "balanceAfter"),
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
        return node == null ? null : textual(node, field);
    }

    /** Text fields must be JSON strings: asText() would silently turn true/numbers/objects into text. */
    private static String textual(JsonNode node, String field) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return node.asText();
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException("occurredAt must be an ISO-8601 instant string");
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("occurredAt is not a valid ISO-8601 instant", e);
        }
    }

    /**
     * An integral JSON number, or integral numeric text, within [min, max]. Never
     * {@code asInt()}/{@code asLong()}, which silently turn garbage into 0 or truncate.
     */
    private static long integral(JsonNode node, String field, long min, long max) {
        BigInteger value;
        if (node.isIntegralNumber()) {
            value = node.bigIntegerValue();
        } else if (node.isTextual() && node.asText().length() <= MAX_NUMERIC_TEXT) {
            try {
                value = new BigInteger(node.asText());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " is not an integer", e);
            }
        } else {
            throw new IllegalArgumentException(field + " must be an integral number");
        }
        if (value.compareTo(BigInteger.valueOf(min)) < 0 || value.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return value.longValueExact();
    }

    /**
     * Exact decimal that must fit numeric(20,4): numbers via their decimal value, strings via
     * their text (length-capped first). Never double. Bounds are checked on the value stripped of
     * trailing zeros, using scale and precision only, so an exponent bomb such as 1e999999999 is
     * rejected without ever being expanded.
     */
    private static BigDecimal money(JsonNode node, String field) {
        BigDecimal value;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isTextual()) {
            String text = node.asText();
            if (text.length() > MAX_NUMERIC_TEXT) {
                throw new IllegalArgumentException(field + " numeric text is too long");
            }
            try {
                value = new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " is not a decimal number", e);
            }
        } else {
            throw new IllegalArgumentException(field + " must be a number");
        }
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        long integerDigits = (long) stripped.precision() - stripped.scale();
        if (stripped.scale() > MONEY_SCALE || integerDigits > MONEY_INTEGER_DIGITS) {
            throw new IllegalArgumentException(field + " does not fit numeric(20,4)");
        }
        // 0e999999999 is a legal zero with an absurd scale; never pass that on.
        return value.signum() == 0 ? BigDecimal.ZERO.setScale(MONEY_SCALE) : value;
    }
}
