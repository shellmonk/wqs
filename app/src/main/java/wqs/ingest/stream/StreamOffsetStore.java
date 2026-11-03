package wqs.ingest.stream;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.projection.OffsetSink;

/**
 * Reads and writes the consumer position. Deliberately in Postgres rather than the broker: the
 * offset advances inside the projection transaction, which is what makes ingestion
 * effectively-once rather than at-least-once plus reconciliation (spec 6.2).
 */
@Component
public class StreamOffsetStore implements OffsetSink {

    private static final String READ = """
            select "offsetVal" from "wqStreamOffset" where "stream" = ?
            """;
    private static final String WRITE = """
            insert into "wqStreamOffset" ("stream", "offsetVal", "updatedAt") values (?, ?, ?)
            on conflict ("stream") do update set "offsetVal" = ?, "updatedAt" = ?
            """;

    private final JdbcClient jdbc;

    public StreamOffsetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> read(String stream) {
        return jdbc.sql(READ).param(stream).query(Long.class).optional();
    }

    @Override
    public void write(String stream, long offset) {
        jdbc.sql(WRITE)
                .params(stream, offset, OffsetDateTime.now(ZoneOffset.UTC),
                        offset, OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }
}
