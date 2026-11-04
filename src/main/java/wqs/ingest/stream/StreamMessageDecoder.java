package wqs.ingest.stream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads a {@code walAccountTransactions} message strictly (format: docs/reference/stream-message-samples.md).
 * Anything malformed throws {@link DecodeException}, whose text never contains a wire value.
 */
@Component
public class StreamMessageDecoder {

    /** The only wire fields copied into "detail". Never "the rest of the message". */
    static final List<String> DETAIL_ALLOWLIST = List.of(
            "subtype", "description", "account", "monetary",
            "accountContext.accountType", "accountContext.accountNumber",
            "references.paymentId", "references.claimId", "references.extTrxId", "references.extSystemId",
            "createdContext.issued", "createdContext.channel", "batchContext.batchEnded",
            "amount.reservedAmount", "amount.nonWithdrawableAmount");

    private static final Pattern OWNER_ID = Pattern.compile("(\\d{1,4})-(\\d{1,18})");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Matches the column type numeric(20,4). */
    private static final int MONEY_SCALE = 4;
    private static final int MONEY_INTEGER_DIGITS = 16;

    private final ObjectReader reader;

    public StreamMessageDecoder(ObjectMapper objectMapper) {
        this.reader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public StreamMessage decode(byte[] body) {
        JsonNode root;
        try {
            root = reader.readTree(body);
        } catch (IOException e) {
            // Jackson's message can quote the input, so it is not passed on.
            throw new DecodeException("not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new DecodeException("not a JSON object");
        }

        JsonNode amount = object(root, "amount", true);
        JsonNode userContext = object(root, "userContext", true);
        JsonNode createdContext = object(root, "createdContext", true);
        JsonNode batchContext = object(root, "batchContext", false);
        JsonNode references = object(root, "references", false);

        var owner = OWNER_ID.matcher(requiredText(userContext, "userContext.ownerId"));
        if (!owner.matches()) {
            throw new DecodeException("userContext.ownerId must be \"{ownerType}-{userId}\"");
        }
        int ownerType = Integer.parseInt(owner.group(1));
        if (ownerType > Short.MAX_VALUE) {
            throw new DecodeException("userContext.ownerId owner type out of range");
        }

        String currency = requiredText(amount, "amount.currency");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new DecodeException("amount.currency must be three upper-case letters");
        }

        return new StreamMessage(
                requiredText(root, "id"),
                (short) ownerType,
                Long.parseLong(owner.group(2)),
                instant(createdContext, "createdContext.created"),
                requiredText(root, "type"),
                money(amount.get("amount"), "amount.amount", true),
                currency,
                money(root.get("totalPostBalance"), "totalPostBalance", false),
                optionalText(root, "productId"),
                optionalText(root, "productType"),
                batchContext == null ? null : optionalText(batchContext, "batchContext.batchId"),
                batchContext == null ? null : optionalText(batchContext, "batchContext.subBatchId"),
                references == null ? null : optionalText(references, "references.transactionReference"),
                detail(root));
    }

    private static JsonNode object(JsonNode parent, String field, boolean required) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw new DecodeException(field + " is missing");
            }
            return null;
        }
        if (!node.isObject()) {
            throw new DecodeException(field + " must be an object");
        }
        return node;
    }

    /** {@code path} is the dotted wire path; its last step is the field inside {@code parent}. */
    private static String optionalText(JsonNode parent, String path) {
        JsonNode node = parent.get(lastStep(path));
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new DecodeException(path + " must be a string");
        }
        String value = noNul(node.textValue(), path).strip();
        return value.isEmpty() ? null : value;
    }

    /** Postgres text and jsonb cannot hold NUL, so a write would fail on it, every time. */
    private static String noNul(String value, String path) {
        if (value.indexOf('\u0000') >= 0) {
            throw new DecodeException(path + " contains a NUL character");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String path) {
        String value = optionalText(parent, path);
        if (value == null) {
            throw new DecodeException(path + " is missing or blank");
        }
        return value;
    }

    /** Truncated to microseconds, the precision of timestamptz, so Java and Postgres agree on the month. */
    private static Instant instant(JsonNode parent, String path) {
        String text = requiredText(parent, path);
        try {
            return Instant.parse(text).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new DecodeException(path + " must be an ISO-8601 instant");
        }
    }

    private static BigDecimal money(JsonNode node, String path, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new DecodeException(path + " is missing");
            }
            return null;
        }
        if (!node.isNumber()) {
            throw new DecodeException(path + " must be a number");
        }
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.scale() > MONEY_SCALE || value.precision() - value.scale() > MONEY_INTEGER_DIGITS) {
            throw new DecodeException(path + " does not fit numeric(20,4)");
        }
        return value.setScale(Math.max(0, value.scale()));
    }

    private static Map<String, String> detail(JsonNode root) {
        Map<String, String> detail = new LinkedHashMap<>();
        for (String path : DETAIL_ALLOWLIST) {
            JsonNode node = root;
            for (String step : path.split("\\.")) {
                node = node == null || !node.isObject() ? null : node.get(step);
            }
            if (node == null || node.isNull() || node.isContainerNode()) {
                continue;
            }
            String value = node.isTextual() ? noNul(node.textValue(), path).strip() : node.asText();
            if (!value.isEmpty()) {
                detail.put(path, value);
            }
        }
        return detail;
    }

    private static String lastStep(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
