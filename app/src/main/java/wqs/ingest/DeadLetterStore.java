package wqs.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.projection.source.StreamPayload;
import wqs.projection.DeltaWatermarkStore;
import wqs.projection.DrainedCancelDeadLetters;
import wqs.projection.RejectedTransactionDeadLetters;

/**
 * Isolates poison events so ingestion keeps flowing (spec 6.4).
 *
 * <p>Every write runs in its OWN transaction (REQUIRES_NEW): the projection transaction has
 * already rolled back by the time we get here, so joining it would roll this record back too and
 * lose the event silently.
 *
 * <p><b>A dead-letter write must not be defeatable by the data it quarantines.</b> If the normal
 * row cannot be stored -- a U+0000 in the sourceId, a payload jsonb rejects -- a second, separate
 * transaction stores a fallback row: null sourceId and {@code {"rawBase64": ...}} of the
 * serialised projected transaction/payload bytes, which any byte sequence survives. Only if that also fails (the
 * database is down) does the exception escape, and then the offset must not move.
 */
@Component
public class DeadLetterStore implements DrainedCancelDeadLetters, RejectedTransactionDeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterStore.class);

    private static final String REPEATED = """
            select exists(
                select 1 from "wqDeadLetter"
                where "stream" = ?
                  and "source" is not distinct from ?
                  and "sourceId" is not distinct from ?
                  and "payload" = cast(? as jsonb))
            """;
    private static final String INSERT = """
            insert into "wqDeadLetter"
                ("stream", "streamOffset", "source", "sourceId", "payload", "error", "attempts")
            values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public DeadLetterStore(JdbcClient jdbc, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records one failed transaction. The payload is the serialised {@link ProjectionTransaction}: a lossless
     * superset of the wire fields we hold, so the row is replayable.
     */
    public void record(String stream, Long offset, ProjectionTransaction transaction, Exception error, int attempts) {
        write(stream, offset, (short) transaction.source().code(), transaction.sourceId(), transaction, error, attempts);
    }

    /**
     * A parked cancel whose application failed when its original arrived (spec 6.3). Its own
     * stream offset is not retained while parked, so the row has none; one attempt, since the
     * drain is not retried on its own.
     */
    @Override
    public void recordFailedDrainedCancel(String stream, ProjectionTransaction cancel, Exception error) {
        record(stream, null, cancel, error, 1);
    }

    /**
     * A transaction an ingest rule rejected (spec 4.1). The error text is the reason exactly
     * ({@code currency-mismatch}, {@code einstant-unsessioned}), so rows can be selected by it; one
     * attempt, since a rule rejects deterministically.
     */
    @Override
    public void recordRejected(String stream, Long offset, ProjectionTransaction transaction, String reason) {
        write(stream, offset, (short) transaction.source().code(), transaction.sourceId(), transaction, reason, 1);
    }

    /** Records an event that could not even be mapped to a projected transaction, using the raw wire payload. */
    public void record(String stream, Long offset, StreamPayload payload, Exception error, int attempts) {
        write(stream, offset, (short) SourceKind.TRANSACTION.code(),
                payload.id(), payload, error, attempts);
    }

    /**
     * Records a message whose body could not be deserialised at all. The payload is
     * {@code {"rawBase64": "..."}}: the exact bytes, so the row is replayable once the cause is fixed.
     */
    public void record(String stream, Long offset, byte[] rawBody, Exception error, int attempts) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        write(stream, offset, (short) SourceKind.TRANSACTION.code(), null, rawBase64(body), error, attempts);
    }

    /**
     * A polled {@code /wallet/delta} row that could not be decoded or applied (phase 6). Only an
     * ALLOWLIST of the row's top-level scalar fields is stored -- {@code allowedFields}, matched
     * exactly -- so card, bank, e-mail and xml/json data ({@code walPayments} holds encrypted PII,
     * plan D4) can never reach a dead letter, whatever its name, case or nesting: a field not on
     * the list, or holding an object or array, is dropped. A row that is not a JSON object is stored
     * as its node type and the SHA-256 of its bytes only. {@code source} and {@code sourceId} are
     * null when the row did not yield them.
     */
    public void recordPolled(
            String stream, JsonNode row, Set<String> allowedFields, SourceKind source, String sourceId, Exception error) {
        write(stream, null, source == null ? null : (short) source.code(), sourceId,
                redact(row, allowedFields), error, 1);
    }

    private Object redact(JsonNode row, Set<String> allowedFields) {
        if (row instanceof ObjectNode object) {
            ObjectNode kept = objectMapper.createObjectNode();
            object.fields().forEachRemaining(field -> {
                if (allowedFields.contains(field.getKey()) && field.getValue().isValueNode()) {
                    kept.set(field.getKey(), field.getValue());
                }
            });
            return kept;
        }
        Map<String, String> marker = new java.util.LinkedHashMap<>();
        marker.put("type", row == null ? "MISSING" : row.getNodeType().name());
        marker.put("sha256", sha256(rawBytes(row)));
        return marker;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(
            String stream, Long offset, Short source, String sourceId,
            Object payload, Exception error, int attempts) {
        write(stream, offset, source, sourceId, payload, describe(error), attempts);
    }

    private void write(
            String stream, Long offset, Short source, String sourceId,
            Object payload, String errorText, int attempts) {
        try {
            requiresNew.executeWithoutResult(status -> {
                String json = serialize(payload);
                if (!isRepeatedPolledRow(stream, source, sourceId, json)) {
                    insert(stream, offset, source, sourceId, json, errorText, attempts);
                }
            });
        } catch (RuntimeException normalFailure) {
            log.warn("Dead-letter row for {} at offset {} could not be stored as is; storing the raw bytes",
                    sanitise(String.valueOf(sourceId)), offset, normalFailure);
            String fallbackError = errorText + "\ndead-letter fallback: " + describe(normalFailure);
            requiresNew.executeWithoutResult(status ->
                    insert(stream, offset, source, null, serialize(rawBase64(rawBytes(payload))),
                            fallbackError, attempts));
        }
    }

    /**
     * The delta poll re-reads its overlap window every run, so the same poison row would be
     * dead-lettered on every poll (final review M-1). A polled row is skipped when a dead letter with
     * the same source, sourceId and payload exists -- the payload carries the row's version field,
     * or for a non-object row its SHA-256 -- so a new version of the row is still recorded.
     */
    private boolean isRepeatedPolledRow(String stream, Short source, String sourceId, String payload) {
        if (!DeltaWatermarkStore.WALLET_DELTA.equals(stream)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.sql(REPEATED)
                .params(text(stream), smallint(source), text(sourceId), payload)
                .query(Boolean.class)
                .single());
    }

    private void insert(
            String stream, Long offset, Short source, String sourceId,
            String payload, String error, int attempts) {
        jdbc.sql(INSERT)
                .params(text(stream), new SqlParameterValue(Types.BIGINT, offset), smallint(source),
                        text(sourceId), payload, sanitise(error), attempts)
                .update();
    }

    // Typed binds, so a null is sent with its column's type rather than an unknown one.
    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    private static SqlParameterValue smallint(Short value) {
        return new SqlParameterValue(Types.SMALLINT, value);
    }

    private static Map<String, String> rawBase64(byte[] bytes) {
        return Map.of("rawBase64", Base64.getEncoder().encodeToString(bytes));
    }

    /** The payload's JSON bytes; if even serialisation fails, its toString -- never nothing. */
    private byte[] rawBytes(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Never write a placeholder: the offset would advance past an event stored nowhere.
            // (The fallback stores rawBytes(payload) instead, which cannot fail.)
            throw new IllegalStateException("cannot serialise dead-letter payload", e);
        }
    }

    /** Postgres text cannot hold U+0000; the error text quotes the very data that caused it. */
    static String sanitise(String text) {
        return text.replace('\0', '\uFFFD');
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.getClass().getName())
                .append(": ").append(error.getMessage());
        Throwable cause = error.getCause();
        while (cause != null) {
            text.append("\ncaused by ").append(cause.getClass().getName())
                    .append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return text.toString();
    }
}
