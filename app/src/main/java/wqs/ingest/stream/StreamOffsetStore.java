package wqs.ingest.stream;

import static wqs.db.generated.Tables.WQ_STREAM_OFFSET;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import wqs.projection.OffsetSink;

/**
 * Reads and writes the consumer position. Deliberately in Postgres rather than the broker: the
 * offset advances inside the projection transaction, which is what makes ingestion
 * effectively-once rather than at-least-once plus reconciliation (spec 6.2).
 */
@Component
public class StreamOffsetStore implements OffsetSink {

    private final DSLContext dsl;

    public StreamOffsetStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Long> read(String stream) {
        return dsl.select(WQ_STREAM_OFFSET.offsetVal)
                .from(WQ_STREAM_OFFSET)
                .where(WQ_STREAM_OFFSET.stream.eq(stream))
                .fetchOptional(WQ_STREAM_OFFSET.offsetVal);
    }

    @Override
    public void write(String stream, long offset) {
        dsl.insertInto(WQ_STREAM_OFFSET)
                .set(WQ_STREAM_OFFSET.stream, stream)
                .set(WQ_STREAM_OFFSET.offsetVal, offset)
                .set(WQ_STREAM_OFFSET.updatedAt, OffsetDateTime.now(ZoneOffset.UTC))
                .onConflict(WQ_STREAM_OFFSET.stream)
                .doUpdate()
                .set(WQ_STREAM_OFFSET.offsetVal, offset)
                .set(WQ_STREAM_OFFSET.updatedAt, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
    }
}
