package wqs.stream;

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
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import wqs.dto.StreamMessage;
import wqs.exception.DecodeException;
import wqs.util.OwnerIds;

/**
 * Reads a {@code walAccountTransactions} message strictly (format: docs/reference/stream-message-samples.md).
 * Anything malformed throws {@link DecodeException}, whose text never contains a wire value.
 */
@Component
public class StreamMessageDecoder {

    /** Lengths of the varchar columns; a longer value is rejected here, not by the insert. */
    private static final int SHORT = 32;
    private static final int ACCOUNT_ID = 64;
    private static final int LONG = 128;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Matches the column type numeric(15,3). */
    private static final int MONEY_SCALE = 3;
    private static final int MONEY_INTEGER_DIGITS = 12;

    private final ObjectReader objectReader;

    public StreamMessageDecoder(ObjectMapper objectMapper) {
        this.objectReader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public StreamMessage decode(byte[] body) {
        JsonNode root;
        try {
            root = objectReader.readTree(body);
        } catch (IOException e) {
            // Jackson's message can quote the input, so it is not passed on.
            throw new DecodeException("not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new DecodeException("not a JSON object");
        }

        JsonNode amount = object(root, "amount", true);
        JsonNode userContext = object(root, "userContext", true);
        JsonNode accountContext = object(root, "accountContext", true);
        JsonNode createdContext = object(root, "createdContext", true);
        JsonNode batchContext = object(root, "batchContext", false);
        JsonNode references = object(root, "references", false);

        String ownerId = OwnerIds.canonical(requiredText(userContext, "userContext.ownerId"))
                .orElseThrow(() -> new DecodeException("userContext.ownerId must be \"{type}-{id}\""));

        String currency = requiredText(amount, "amount.currency");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new DecodeException("amount.currency must be three upper-case letters");
        }

        return new StreamMessage(
                requiredText(root, "id"),
                ownerId,
                instant(createdContext, "createdContext.created"),
                limited(requiredText(accountContext, "accountContext.accountType"), SHORT, "accountContext.accountType"),
                limited(requiredText(accountContext, "accountContext.accountNumber"), SHORT, "accountContext.accountNumber"),
                limited(requiredText(root, "account"), ACCOUNT_ID, "account"),
                optionalText(batchContext, "batchContext.batchId"),
                optionalText(batchContext, "batchContext.subBatchId"),
                bool(batchContext, "batchContext.batchEnded"),
                requiredText(root, "type"),
                limited(optionalText(root, "subtype"), SHORT, "subtype"),
                optionalText(root, "productId"),
                optionalText(root, "productType"),
                currency,
                money(amount.get("amount"), "amount.amount", true),
                money(root.get("totalPostBalance"), "totalPostBalance", false),
                money(amount.get("reservedAmount"), "amount.reservedAmount", false),
                money(amount.get("nonWithdrawableAmount"), "amount.nonWithdrawableAmount", false),
                !Boolean.FALSE.equals(bool(root, "monetary")), // absent: monetary, as the column's default
                limited(optionalText(root, "description"), LONG, "description"),
                limited(optionalText(root, "fromDescription"), LONG, "fromDescription"),
                limited(optionalText(root, "toDescription"), LONG, "toDescription"),
                id(references, "references.paymentId"),
                id(references, "references.claimId"),
                limited(optionalText(references, "references.extTrxId"), LONG, "references.extTrxId"),
                limited(optionalText(references, "references.extSystemId"), SHORT, "references.extSystemId"),
                optionalText(references, "references.transactionReference"),
                limited(optionalText(createdContext, "createdContext.channel"), SHORT, "createdContext.channel"),
                instant(createdContext, "createdContext.issued"));
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
        JsonNode node = parent == null ? null : parent.get(lastStep(path));
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
        // long arithmetic: an exponent like 1E+2147483647 has scale -2147483647 and would overflow int.
        if (value.scale() > MONEY_SCALE || (long) value.precision() - value.scale() > MONEY_INTEGER_DIGITS) {
            throw new DecodeException(path + " does not fit numeric(15,3)");
        }
        return value.setScale(Math.max(0, value.scale()));
    }

    /** {@code value} unless it is longer than its column allows. */
    private static String limited(String value, int maxLength, String path) {
        if (value != null && value.length() > maxLength) {
            throw new DecodeException(path + " is longer than " + maxLength + " characters");
        }
        return value;
    }

    private static Boolean bool(JsonNode parent, String path) {
        JsonNode node = parent == null ? null : parent.get(lastStep(path));
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new DecodeException(path + " must be true or false");
        }
        return node.booleanValue();
    }

    /**
     * A wallet id such as {@code references.paymentId}. The wire sends it as a string ("" when
     * there is none); a whole number is accepted too.
     */
    private static Long id(JsonNode parent, String path) {
        JsonNode node = parent == null ? null : parent.get(lastStep(path));
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        String text = optionalText(parent, path); // rejects any other non-string
        if (text == null) {
            return null;
        }
        if (text.length() > 19 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new DecodeException(path + " must be a whole number");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new DecodeException(path + " must be a whole number");
        }
    }

    private static String lastStep(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
