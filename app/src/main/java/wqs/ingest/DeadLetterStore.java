package wqs.ingest;

import static wqs.db.generated.Tables.WQ_DEAD_LETTER;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.ingest.stream.StreamPayload;

/**
 * Isolates poison events so ingestion keeps flowing (spec 6.4).
 *
 * <p>REQUIRES_NEW is essential: the projection transaction has already rolled back by the time we
 * get here, so joining it would roll this record back too and lose the event silently.
 */
@Component
public class DeadLetterStore {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public DeadLetterStore(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one failed item. The payload is the serialised {@link ProjectionItem}: a lossless
     * superset of the wire fields we hold, so the row is replayable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String stream, Long offset, ProjectionItem item, Exception error, int attempts) {
        insert(stream, offset, (short) item.source().code(), item.sourceId(), item, error, attempts);
    }

    /** Records an event that could not even be mapped to an item, using the raw wire payload. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String stream, Long offset, StreamPayload payload, Exception error, int attempts) {
        insert(stream, offset, (short) SourceKind.TRANSACTION.code(),
                payload.transactionId(), payload, error, attempts);
    }

    private void insert(
            String stream, Long offset, short source, String sourceId,
            Object payload, Exception error, int attempts) {
        dsl.insertInto(WQ_DEAD_LETTER)
                .set(WQ_DEAD_LETTER.stream, stream)
                .set(WQ_DEAD_LETTER.streamOffset, offset)
                .set(WQ_DEAD_LETTER.source, source)
                .set(WQ_DEAD_LETTER.sourceId, sourceId)
                .set(WQ_DEAD_LETTER.payload, serialize(payload))
                .set(WQ_DEAD_LETTER.error, describe(error))
                .set(WQ_DEAD_LETTER.attempts, attempts)
                .execute();
    }

    private JSONB serialize(Object payload) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            return JSONB.valueOf("{\"unserializable\":true}");
        }
    }

    private static String describe(Exception error) {
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
