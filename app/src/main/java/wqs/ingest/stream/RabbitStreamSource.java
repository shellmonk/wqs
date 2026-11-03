package wqs.ingest.stream;

import static wqs.ingest.StrictJson.digits;
import static wqs.ingest.StrictJson.instant;
import static wqs.ingest.StrictJson.money;
import static wqs.ingest.StrictJson.object;
import static wqs.ingest.StrictJson.optional;
import static wqs.ingest.StrictJson.required;
import static wqs.ingest.StrictJson.text;
import static wqs.ingest.StrictJson.textual;
import static wqs.ingest.StrictJson.typeCode;

import wqs.ingest.StrictJson;
import wqs.projection.source.StreamPayload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.NoOffsetException;
import java.nio.charset.StandardCharsets;
import com.rabbitmq.stream.OffsetSpecification;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Real stream subscription. Offset tracking is NOT delegated to the broker -- the offset is
 * persisted by the projection transaction instead (spec 6.2), so this only chooses where to
 * start reading.
 *
 * <p>Not a Spring component itself: {@link StreamConfig} builds it (only when the stream is enabled).
 *
 * <p>Decodes the real {@code walAccountTransactions} format (spec 4.1, docs/reference/stream-message-samples.md):
 * nested JSON, parsed through a tree so each field is checked strictly. Only the D9 allowlist
 * reaches "detail". Any malformed message throws and is routed to the dead-letter path.
 */
public class RabbitStreamSource implements StreamSource {

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

    /**
     * The broker's {@code committedChunkId}: the offset of the FIRST message of the last committed
     * chunk -- the closest head the client exposes (0.18 has no "last offset"). The lag computed
     * from it is therefore a lower bound, short by at most one chunk.
     */
    @Override
    public OptionalLong headOffset() {
        try {
            return OptionalLong.of(environment.queryStreamStats(properties.name()).committedChunkId());
        } catch (NoOffsetException e) {
            return OptionalLong.of(-1); // the stream is empty
        } catch (RuntimeException e) {
            return OptionalLong.empty();
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

    static StreamPayload parse(ObjectMapper objectMapper, byte[] body) {
        JsonNode root;
        try {
            root = StrictJson.reader(objectMapper).readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("undeserializable stream message", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("stream message is not a JSON object");
        }

        JsonNode userContext = object(root, "userContext", true);
        JsonNode createdContext = object(root, "createdContext", true);
        JsonNode amount = object(root, "amount", true);
        JsonNode batchContext = object(root, "batchContext", false);
        JsonNode references = object(root, "references", false);

        String ownerId = textual(required(userContext, "userContext.ownerId", "ownerId"), "userContext.ownerId");
        int dash = ownerId.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("userContext.ownerId must be \"{userType}-{userId}\"");
        }
        int userType = (int) digits(ownerId.substring(0, dash), "userContext.ownerId userType", Integer.MAX_VALUE);
        long userId = digits(ownerId.substring(dash + 1), "userContext.ownerId userId", Long.MAX_VALUE);

        return new StreamPayload(
                textual(required(root, "id"), "id"),
                userType,
                userId,
                instant(required(createdContext, "createdContext.created", "created"), "createdContext.created"),
                typeCode(textual(required(root, "type"), "type"), "type"),
                text(root, "productId"),
                text(root, "productType"),
                batchContext == null ? null : text(batchContext, "batchContext.batchId", "batchId"),
                batchContext == null ? null : text(batchContext, "batchContext.subBatchId", "subBatchId"),
                money(required(amount, "amount.amount", "amount"), "amount.amount"),
                textual(required(amount, "amount.currency", "currency"), "amount.currency"),
                optional(root, "totalPostBalance") == null ? null
                        : money(root.get("totalPostBalance"), "totalPostBalance"),
                references == null ? null : text(references, "references.transactionReference", "transactionReference"),
                detail(root));
    }

    /**
     * The D9 allowlist: the only wire fields that reach "detail", under dotted keys. Anything
     * else is dropped -- never "the rest of the row" (walPayments carries encrypted card data).
     * Empty strings are dropped like every other blank; scalars are carried as their text.
     */
    private static final java.util.List<String> DETAIL_ALLOWLIST = java.util.List.of(
            "subtype", "description", "fromDescription", "toDescription", "account",
            "accountContext.accountType", "accountContext.accountNumber", "monetary",
            "references.paymentId", "references.claimId", "references.extTrxId", "references.extSystemId",
            "createdContext.issued", "createdContext.channel", "batchContext.batchEnded",
            "amount.reservedAmount", "amount.nonWithdrawableAmount");

    private static Map<String, String> detail(JsonNode root) {
        Map<String, String> detail = new java.util.LinkedHashMap<>();
        for (String path : DETAIL_ALLOWLIST) {
            JsonNode node = root;
            for (String step : path.split("\\.")) {
                node = node == null || !node.isObject() ? null : node.get(step);
            }
            if (node == null || node.isNull() || node.isContainerNode()) {
                continue;
            }
            String value = node.isTextual() ? textual(node, path) : node.asText();
            if (!value.isEmpty()) {
                detail.put(path, value);
            }
        }
        return detail;
    }
}
