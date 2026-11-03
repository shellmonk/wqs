package wqs.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared Postgres 16 container with the full migration set applied once per JVM. */
public abstract class PostgresTestBase {

    // No .withReuse(true) here: Testcontainers only honors reuse when the developer has opted
    // in via `testcontainers.reuse.enable=true` in their own ~/.testcontainers.properties
    // (deliberately not settable from the project, so CI can't be forced into it). Requesting
    // it without that opt-in just logs a WARN on every run. A developer who wants reuse locally
    // can set that property for themselves and re-add the call here.
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static DataSource dataSource;

    static {
        POSTGRES.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    /** Plain-SQL helpers over {@link #jdbc()}, for test setup and assertions. */
    protected static TestSql db() {
        return new TestSql(jdbc());
    }

    /** A JdbcClient over the shared container; it joins a Spring transaction on {@link #dataSource()}. */
    protected static org.springframework.jdbc.core.simple.JdbcClient jdbc() {
        return org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    }

    /**
     * A transaction template over the shared container, for tests that build updaters by hand: a
     * {@link #jdbc()} client joins the transaction it opens, as the app's writer does.
     */
    protected static org.springframework.transaction.support.TransactionTemplate inTx() {
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    private static volatile TxTypeRegistry txTypes;

    /**
     * A transaction-type registry over the shared container, for tests that build updaters by
     * hand. Lookups of seeded types never write; an unseen code is registered in its own
     * transaction, as in the app.
     */
    protected static TxTypeRegistry txTypes() {
        if (txTypes == null) {
            txTypes = new TxTypeRegistry(jdbc(),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        }
        return txTypes;
    }

    /**
     * Empties every table the projection writer maintains, including the stream offset it
     * commits and the dead letters the ingest path records. The container is shared by all test classes, so clearing only some of them leaves
     * derived state (rollups, page index, counters)
     * pointing at rows that no longer exist -- e.g. a session locator without its session, or a
     * page-index row whose counters another class deleted -- which the strict updaters then
     * (correctly) reject.
     */
    protected static void clearProjection() {
        for (String table : java.util.List.of(
                "wqItemLocator", "wqWebItem", "wqCcItem", "wqGroupLocator", "wqSession", "wqPlay",
                "wqWebHighLevel", "wqCcHighLevel", "wqCountDay", "wqCountMonth", "wqPendingCancel",
                "wqStreamOffset", "wqDeadLetter", "wqIngestWatermark")) {
            db().execute("delete from \"" + table + "\"");
        }
    }
}
