package wqs.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs partition DDL in its own short transaction (REQUIRES_NEW). Creating a partition takes an
 * exclusive lock on the parent table, and that lock must not be held for the rest of an ingest
 * transaction.
 *
 * <p>The DDL waits at most {@value #LOCK_TIMEOUT} for that lock. A transaction of this application
 * that has already written to the parent (a batch that reached a new month) holds a lock the DDL
 * would wait for forever, while it waits for the DDL in Java: Postgres cannot see that deadlock.
 * The timeout turns it into an error; callers create partitions before writing to avoid it.
 * DDL goes through {@link JdbcClient}: JPA has no DDL.
 */
@Service
public class PartitionDdlService {

    private final JdbcClient jdbcClient;

    public PartitionDdlService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    static final String LOCK_TIMEOUT = "5s";

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(String ddl) {
        jdbcClient.sql("set local lock_timeout = '" + LOCK_TIMEOUT + "'").update();
        jdbcClient.sql(ddl).update();
    }

    /** Runs in the caller's transaction. */
    public boolean exists(String partition) {
        return jdbcClient.sql("select to_regclass(:name) is not null")
                .param("name", "\"" + partition + "\"")
                .query(Boolean.class).single();
    }
}
