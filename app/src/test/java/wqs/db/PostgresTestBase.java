package wqs.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared Postgres 16 container with the full migration set applied once per JVM. */
public abstract class PostgresTestBase {

    // No .withReuse(true) here: Testcontainers only honors reuse when the developer has opted
    // in via `testcontainers.reuse.enable=true` in their own ~/.testcontainers.properties
    // (deliberately not settable from the project, so CI can't be forced into it). Requesting
    // it without that opt-in just logs a WARN on every run. A developer who wants reuse locally
    // can set that property for themselves and re-add the call here.
    //
    // max_connections is raised because Spring's test-context cache keeps one Hikari pool (10
    // connections, kept idle) per distinct context configuration alive for the whole JVM, and the
    // suite has more distinct contexts than the default 100 connections can hold.
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCommand("postgres", "-c", "max_connections=400");

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

    protected static DSLContext dsl() {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
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
                "wqStreamOffset", "wqDeadLetter")) {
            dsl().execute("delete from \"" + table + "\"");
        }
    }
}
