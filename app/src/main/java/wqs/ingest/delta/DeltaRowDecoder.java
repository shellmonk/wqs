package wqs.ingest.delta;

import static wqs.ingest.StrictJson.detailScalar;
import static wqs.ingest.StrictJson.integral;
import static wqs.ingest.StrictJson.money;
import static wqs.ingest.StrictJson.optional;
import static wqs.ingest.StrictJson.required;
import static wqs.ingest.StrictJson.text;
import static wqs.ingest.StrictJson.textual;
import static wqs.ingest.StrictJson.typeCode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.projection.source.DeltaRow;

/**
 * Decodes one {@code /wallet/delta} row -- the union of the four polled tables' columns, keyed by
 * their camelCase DDL names, plus {@code source} (phase 6 plan D1) -- into a {@link DeltaRow}.
 *
 * <p>Field mapping per plan D3, "detail" per the D4 allowlist (never card, bank, e-mail or xml
 * data: {@code walPayments} holds encrypted PII). Every check is strict ({@link wqs.ingest.StrictJson}):
 * an unknown {@code source}, a missing required field, a malformed owner or a bad number or
 * timestamp throws {@link IllegalArgumentException}, which the poller dead-letters as poison.
 *
 * <p>The owner is decoded for every row, players or not, and every owner is stored with its type
 * (a claimable prize's {@code playerId} is a player by definition: type 1); which owners an
 * endpoint shows is the read policy's decision. A malformed owner is poison, as on the stream.
 *
 * <p>One class with golden fixtures on purpose: when a real sample of the endpoint arrives, only
 * this class, its fixtures and {@link DeltaProperties} change.
 */
@Component
public class DeltaRowDecoder {

    /** The synthetic type code of every claimable prize, which has no type column (D3). */
    public static final String CLAIMABLE_PRIZE_TYPE = "CLAIMABLE_PRIZE";
    /** ISO 4217 "no currency", for a row whose currency is null (a count-based limit). */
    public static final String NO_CURRENCY = "XXX";

    /** The synthetic type code of a payment whose {@code paymentType} is null or blank (ruling R-b). */
    public static final String PAYMENT_TYPE_FALLBACK = "PAYMENT";

    private static final int PLAYER = 1;

    /** A zoneless source {@code timestamp} (ruling R-c), read as UTC. */
    private static final DateTimeFormatter LOCAL_TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper();

    private static final List<String> PAYMENT_DETAIL = List.of(
            "paymentType", "paymentInOut", "status", "statusDate", "channel", "subChannel", "description",
            "grossAmount", "serviceCost", "workflowStatus", "reviewHandling", "approved", "verified");
    private static final List<String> PRIZE_DETAIL = List.of(
            "status", "prizeLevel", "wagerAmount", "onHold", "holdReason", "workflowStatus",
            "claimExpiryTimestamp", "prizeClaimedTimestamp", "claimEndedTimestamp");
    private static final List<String> ADJUSTMENT_DETAIL = List.of(
            "operation", "transactionType", "transactionSubtype", "status", "statusDate", "reason",
            "approved", "rejected");
    private static final List<String> LIMIT_DETAIL = List.of(
            "quantityType", "limitType", "period", "count", "gameGroup", "paymentMethodType", "channel",
            "effectiveFrom", "effectiveUntil", "approved", "reason");

    /**
     * The wire fields a poison row's dead letter may keep (security review): what the decoder reads
     * -- source, ids, owners, timestamps, amounts, currency, type and product/batch fields -- and the
     * D4 detail allowlists. Everything else (card, bank, e-mail, account, xml/json) is never stored.
     */
    public static final java.util.Set<String> DEAD_LETTER_FIELDS;

    static {
        java.util.Set<String> fields = new java.util.LinkedHashSet<>(List.of(
                "source", "paymentId", "id", "ownerType", "ownerId", "playerId", "userType", "userId",
                "created", "issued", "updated", "statusDate", "amount", "prizeAmount", "currency",
                "paymentType", "paymentInOut", "operation", "transactionType", "limitType",
                "productId", "productType", "batchId", "subBatchId"));
        fields.addAll(PAYMENT_DETAIL);
        fields.addAll(PRIZE_DETAIL);
        fields.addAll(ADJUSTMENT_DETAIL);
        fields.addAll(LIMIT_DETAIL);
        DEAD_LETTER_FIELDS = java.util.Set.copyOf(fields);
    }

    private final DeltaProperties properties;

    public DeltaRowDecoder(DeltaProperties properties) {
        this.properties = properties;
    }

    /**
     * The type code is kept as-is (plan D1): an unknown code is projected, NEUTRAL.
     *
     * @throws IllegalArgumentException if the row is poison
     */
    public DeltaRow decode(JsonNode row) {
        if (row == null || !row.isObject()) {
            throw new IllegalArgumentException("delta row is not a JSON object");
        }
        String sourceValue = textual(required(row, "source"), "source");
        SourceKind source = properties.sourceOf(sourceValue)
                .orElseThrow(() -> new IllegalArgumentException("unknown delta source: " + sourceValue));
        return switch (source) {
            case PAYMENT -> payment(row);
            case CLAIMABLE_PRIZE -> prize(row);
            case MANUAL_ADJUSTMENT -> adjustment(row);
            case LIMIT -> limit(row);
            case TRANSACTION -> throw new IllegalArgumentException("transactions are never polled");
        };
    }

    /**
     * What can still be read from a row {@link #decode} rejected, for its dead-letter record and
     * the poller's watermark: each part is null when it is itself unreadable. Never throws.
     *
     * @param source   the row's mapped kind
     * @param sourceId its id field ({@code paymentId} for payments, {@code id} otherwise)
     * @param version  its version field, so one poison row cannot pin the cursor
     */
    public record Identity(SourceKind source, String sourceId, Instant version) {}

    public Identity identify(JsonNode row) {
        if (row == null || !row.isObject()) {
            return new Identity(null, null, null);
        }
        SourceKind source = attempt(() -> properties.sourceOf(textual(required(row, "source"), "source")).orElse(null));
        if (source == null || source == SourceKind.TRANSACTION) {
            return new Identity(null, null, null);
        }
        String idField = source == SourceKind.PAYMENT ? "paymentId" : "id";
        String versionField = source == SourceKind.MANUAL_ADJUSTMENT ? "statusDate" : "updated";
        return new Identity(source,
                attempt(() -> Long.toString(id(row, idField))),
                attempt(() -> timestamp(row, versionField)));
    }

    private static <T> T attempt(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static DeltaRow payment(JsonNode row) {
        int ownerType = ownerType(row, "ownerType");
        long userId = id(row, "ownerId");
        long direction = integral(required(row, "paymentInOut"), "paymentInOut", Integer.MAX_VALUE);
        BigDecimal amount = money(required(row, "amount"), "amount").abs();
        if (direction == 2) {
            amount = amount.negate();
        } else if (direction != 1) {
            throw new IllegalArgumentException("paymentInOut must be 1 (IN) or 2 (OUT)");
        }
        return row(SourceKind.PAYMENT, ownerType, userId, row, "paymentId", "created", "updated",
                amount, paymentType(row),
                null, null, null, null, PAYMENT_DETAIL);
    }

    private static DeltaRow prize(JsonNode row) {
        return row(SourceKind.CLAIMABLE_PRIZE, PLAYER, id(row, "playerId"), row, "id", "created", "updated",
                money(required(row, "prizeAmount"), "prizeAmount"), CLAIMABLE_PRIZE_TYPE,
                text(row, "productId"), text(row, "productType"), text(row, "batchId"), text(row, "subBatchId"),
                PRIZE_DETAIL);
    }

    /** No {@code created} column: the request's {@code issued} is when it came to exist; no {@code updated}: {@code statusDate}. */
    private static DeltaRow adjustment(JsonNode row) {
        int ownerType = ownerType(row, "userType");
        long userId = id(row, "userId");
        String operation = textual(required(row, "operation"), "operation");
        BigDecimal amount = money(required(row, "amount"), "amount").abs();
        switch (operation) {
            case "DEPOSIT" -> { }
            case "WITHDRAWAL" -> amount = amount.negate();
            default -> throw new IllegalArgumentException("operation must be DEPOSIT or WITHDRAWAL");
        }
        return row(SourceKind.MANUAL_ADJUSTMENT, ownerType, userId, row, "id", "issued", "statusDate",
                amount, typeCode(textual(required(row, "transactionType"), "transactionType"), "transactionType"),
                null, null, null, null, ADJUSTMENT_DETAIL);
    }

    private static DeltaRow limit(JsonNode row) {
        int ownerType = ownerType(row, "userType");
        long userId = id(row, "userId");
        JsonNode amount = optional(row, "amount");
        return row(SourceKind.LIMIT, ownerType, userId, row, "id", "created", "updated",
                amount == null ? BigDecimal.ZERO : money(amount, "amount"),
                typeCode(textual(required(row, "limitType"), "limitType"), "limitType"),
                null, null, null, null, LIMIT_DETAIL);
    }

    private static DeltaRow row(
            SourceKind source, int ownerType, long userId, JsonNode row,
            String idField, String occurredAtField, String versionField,
            BigDecimal amount, String typeCode,
            String productId, String productType, String batchId, String subBatchId,
            List<String> detailFields) {
        String sourceId = Long.toString(id(row, idField));
        Instant occurredAt = timestamp(row, occurredAtField);
        Instant version = timestamp(row, versionField);
        String currency = text(row, "currency");
        String detail = detail(row, detailFields);
        ProjectionTransaction transaction = new ProjectionTransaction(
                (short) ownerType, userId, source, sourceId, occurredAt, productId, productType, batchId, subBatchId,
                typeCode, amount, currency == null ? NO_CURRENCY : currency,
                null, null, detail);
        return new DeltaRow(source, ownerType, userId, version, transaction);
    }

    private static String paymentType(JsonNode row) {
        String code = text(row, "paymentType");
        return code == null || code.isBlank() ? PAYMENT_TYPE_FALLBACK : typeCode(code, "paymentType");
    }

    /**
     * A delta timestamp: an instant with {@code Z} or an offset, or a zoneless source
     * {@code timestamp} read as UTC (ruling R-c); anything else is poison. Truncated to
     * microseconds -- the precision of timestamptz -- so a stored value always compares equal to a
     * re-read of the same wire value (never rounded differently by the driver).
     */
    static Instant timestamp(JsonNode row, String field) {
        JsonNode node = required(row, field);
        String text = textual(node, field);
        Instant at;
        try {
            at = Instant.parse(text);
        } catch (DateTimeParseException zoned) {
            try {
                // "2026-10-29 11:22:04.465": a space separator is accepted as the ISO 'T'.
                String iso = text.length() > 10 && text.charAt(10) == ' '
                        ? text.substring(0, 10) + 'T' + text.substring(11) : text;
                LocalDateTime local = LocalDateTime.parse(iso, LOCAL_TIMESTAMP);
                at = local.toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + " is not a valid timestamp", e);
            }
        }
        return at.truncatedTo(ChronoUnit.MICROS);
    }

    private static int ownerType(JsonNode row, String field) {
        // "ownerType" is a smallint: a larger value is poison, never silently narrowed.
        return (int) integral(required(row, field), field, Short.MAX_VALUE);
    }

    private static long id(JsonNode row, String field) {
        return integral(required(row, field), field, Long.MAX_VALUE);
    }

    /** Only the allowlisted fields, as text; null, blank and container values are dropped. */
    private static String detail(JsonNode row, List<String> fields) {
        Map<String, String> detail = new LinkedHashMap<>();
        for (String field : fields) {
            String value = detailScalar(row, field);
            if (value != null) {
                detail.put(field, value);
            }
        }
        if (detail.isEmpty()) {
            return null;
        }
        try {
            return DETAIL_MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialise detail", e);
        }
    }
}
