package wqs.projection;

import static wqs.db.generated.Tables.WQ_INGEST_WATERMARK;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delta poller's cursor in "wqIngestWatermark" (phase 6 plan D5, D7). One row: the endpoint
 * returns all four polled sources in one stream of pages, so they share one watermark.
 */
@Component
public class DeltaWatermarkStore {

    /** The "source" key of the {@code /wallet/delta} watermark row. */
    public static final String WALLET_DELTA = "wallet-delta";

    private final DSLContext dsl;

    public DeltaWatermarkStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Instant> read() {
        return dsl.select(WQ_INGEST_WATERMARK.watermark)
                .from(WQ_INGEST_WATERMARK)
                .where(WQ_INGEST_WATERMARK.source.eq(WALLET_DELTA))
                .fetchOptional(WQ_INGEST_WATERMARK.watermark)
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Upserts the watermark in the caller's transaction, so it commits or rolls back with the
     * batch's items. Never moves it backwards: a page holding only overlap rows has a max version
     * below the stored watermark, and re-reading further back each run would be pointless work.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Instant watermark) {
        OffsetDateTime at = watermark.atOffset(ZoneOffset.UTC);
        dsl.insertInto(WQ_INGEST_WATERMARK)
                .set(WQ_INGEST_WATERMARK.source, WALLET_DELTA)
                .set(WQ_INGEST_WATERMARK.watermark, at)
                .onConflict(WQ_INGEST_WATERMARK.source)
                .doUpdate()
                .set(WQ_INGEST_WATERMARK.watermark,
                        DSL.greatest(WQ_INGEST_WATERMARK.watermark, DSL.excluded(WQ_INGEST_WATERMARK.watermark)))
                .set(WQ_INGEST_WATERMARK.updatedAt, DSL.currentOffsetDateTime())
                .execute();
    }
}
