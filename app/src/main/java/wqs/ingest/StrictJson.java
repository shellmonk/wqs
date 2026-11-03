package wqs.ingest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Strict JSON field extraction shared by every ingest decoder (the stream's
 * {@code RabbitStreamSource} and the delta poller's {@code DeltaRowDecoder}). Every helper throws
 * {@link IllegalArgumentException} on anything it does not accept, which the callers treat as
 * poison: never {@code asText()}/{@code asLong()} coercion, never a double.
 */
public final class StrictJson {

    /** Cap on raw numeric text before it is handed to a parser (numeric(20,4) needs < 30 chars). */
    private static final int MAX_NUMERIC_TEXT = 64;
    private static final int MONEY_SCALE = 4;
    private static final int MONEY_INTEGER_DIGITS = 16; // numeric(20,4)

    /**
     * Every real transaction type code is upper-case letters, digits and underscores, at most 64
     * characters (walAccountTransactions.transactionType is varchar(64), source-schema.sql). The
     * registry inserts a first-sighted code; anything else is rejected HERE as poison, so a code
     * the database would refuse can never become a retry-forever failure downstream.
     */
    private static final Pattern TYPE_CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    private StrictJson() {}

    /** BigDecimal floats with trailing zeros kept: "0.1000" stays scale 4, not double / 0.1. */
    public static ObjectReader reader(ObjectMapper objectMapper) {
        return objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    public static String typeCode(String code, String field) {
        if (!TYPE_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(field + " must match [A-Z0-9_]{1,64}");
        }
        return code;
    }

    /** An object-valued field; absent/null is only allowed when not required. */
    public static JsonNode object(JsonNode root, String field, boolean required) {
        JsonNode node = required ? required(root, field) : optional(root, field);
        if (node != null && !node.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return node;
    }

    /** Decimal digits only, within [0, max]: never asInt()/asLong(), never a sign or whitespace. */
    public static long digits(String text, String field, long max) {
        if (text.isEmpty() || text.length() > MAX_NUMERIC_TEXT || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException(field + " is not a number");
        }
        BigInteger value = new BigInteger(text);
        if (value.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return value.longValueExact();
    }

    /**
     * A non-negative integer within [0, max] given as a JSON integral number or as a string of
     * digits (a bigint column may be serialised either way). Never a float, a sign or a boolean.
     */
    public static long integral(JsonNode node, String field, long max) {
        if (node.isIntegralNumber()) {
            return digits(node.bigIntegerValue().toString(), field, max);
        }
        if (node.isTextual()) {
            return digits(node.asText(), field, max);
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    public static JsonNode optional(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node;
    }

    public static JsonNode required(JsonNode root, String field) {
        return required(root, field, field);
    }

    /** {@code label} is the full path used in the error message. */
    public static JsonNode required(JsonNode root, String label, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            throw new IllegalArgumentException("message is missing required field: " + label);
        }
        return node;
    }

    public static String text(JsonNode root, String field) {
        return text(root, field, field);
    }

    /** Optional text; {@code label} is the full path used in error messages. Every empty string is null (spec 4.1). */
    public static String text(JsonNode root, String label, String field) {
        JsonNode node = optional(root, field);
        if (node == null) {
            return null;
        }
        String value = textual(node, label);
        return value.isEmpty() ? null : value;
    }

    /**
     * Text fields must be JSON strings: asText() would silently turn true/numbers/objects into text.
     *
     * <p>U+0000 is legal JSON but Postgres can store it neither in text nor in jsonb, so an
     * accepted NUL would make the item insert AND its dead-letter row fail on every retry --
     * halting ingestion for good. Rejected here it becomes poison.
     */
    public static String textual(JsonNode node, String field) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String text = node.asText();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " contains U+0000, which Postgres cannot store");
        }
        return text;
    }

    public static Instant instant(JsonNode node, String field) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant string");
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not a valid ISO-8601 instant", e);
        }
    }

    /**
     * Exact decimal that must fit numeric(20,4): numbers via their decimal value, strings via
     * their text (length-capped first). Never double. Bounds are checked on the value stripped of
     * trailing zeros, using scale and precision only, so an exponent bomb such as 1e999999999 is
     * rejected without ever being expanded.
     */
    public static BigDecimal money(JsonNode node, String field) {
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

    /**
     * A scalar carried into "detail" as text: strings checked by {@link #textual}, numbers and
     * booleans as their JSON text. Null, absent, containers and the empty string give null.
     */
    public static String detailScalar(JsonNode root, String field) {
        JsonNode node = optional(root, field);
        if (node == null || node.isContainerNode()) {
            return null;
        }
        String value = node.isTextual() ? textual(node, field) : node.asText();
        return value.isEmpty() ? null : value;
    }
}
