package wqs.projection;

import static wqs.projection.Binds.timestamptz;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delta poller's cursor: the "watermark" of the "wqIngestCursor" row whose "feed" is
 * {@value #WALLET_DELTA} (phase 6 plan D5, D7). One row: the endpoint
 * returns all four polled sources in one stream of pages, so they share one watermark.
 */
@Component
public class DeltaWatermarkStore {

    /** The "feed" key of the {@code /wallet/delta} watermark row. */
    public static final String WALLET_DELTA = "wallet-delta";

    private static final String READ = """
            select "watermark" from "wqIngestCursor" where "feed" = ? and "watermark" is not null
            """;
    private static final String WRITE = """
            insert into "wqIngestCursor" ("feed", "watermark") values (?, ?)
            on conflict ("feed") do update set
                "watermark" = greatest("wqIngestCursor"."watermark", excluded."watermark"),
                "updatedAt" = current_timestamp
            """;

    private final JdbcClient jdbc;

    public DeltaWatermarkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> read() {
        return jdbc.sql(READ)
                .param(WALLET_DELTA)
                .query((rs, n) -> rs.getObject("watermark", OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Upserts the watermark in the caller's transaction, so it commits or rolls back with the
     * batch's projected transactions. Never moves it backwards: a page holding only overlap rows has a max version
     * below the stored watermark, and re-reading further back each run would be pointless work.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Instant watermark) {
        OffsetDateTime at = watermark.atOffset(ZoneOffset.UTC);
        jdbc.sql(WRITE).params(WALLET_DELTA, timestamptz(at)).update();
    }
}
