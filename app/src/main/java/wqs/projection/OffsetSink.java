package wqs.projection;

/**
 * Where the writer commits the consumer position, inside the projection transaction (spec 6.2
 * step 9). Projection-owned so the writer does not depend on the ingest package, which owns the
 * offset store (spec 10, 8.5 layering: {@code ingest -> projection}, never the reverse).
 */
public interface OffsetSink {

    /** Must join the caller's transaction: the offset advances or rolls back with the batch. */
    void write(String stream, long offset);
}
