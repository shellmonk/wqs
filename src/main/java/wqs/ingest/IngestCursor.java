package wqs.ingest;

import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The last processed position of each feed, stored in the same transaction as what it produced. */
@Component
public class IngestCursor {

    private final JdbcClient jdbc;

    public IngestCursor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public OptionalLong streamOffset(String feed) {
        return jdbc.sql("select \"streamOffset\" from \"wqIngestCursor\" where \"feed\" = :feed")
                .param("feed", feed)
                .query(Long.class).optional()
                .map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveStreamOffset(String feed, long offset) {
        jdbc.sql("""
                insert into "wqIngestCursor" ("feed", "streamOffset", "updatedAt") values (:feed, :offset, now())
                on conflict ("feed") do update set "streamOffset" = excluded."streamOffset", "updatedAt" = now()
                """)
                .param("feed", feed)
                .param("offset", offset)
                .update();
    }
}
