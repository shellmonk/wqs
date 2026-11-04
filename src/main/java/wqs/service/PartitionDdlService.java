package wqs.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs partition DDL in its own short transaction (REQUIRES_NEW). Creating a partition locks the
 * parent table, and that lock must not be held for the rest of an ingest transaction.
 * DDL goes through {@link JdbcClient}: JPA has no DDL.
 */
@Service
public class PartitionDdlService {

    private final JdbcClient jdbc;

    public PartitionDdlService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(String ddl) {
        jdbc.sql(ddl).update();
    }

    /** Runs in the caller's transaction. */
    public boolean exists(String partition) {
        return jdbc.sql("select to_regclass(:name) is not null")
                .param("name", "\"" + partition + "\"")
                .query(Boolean.class).single();
    }
}
