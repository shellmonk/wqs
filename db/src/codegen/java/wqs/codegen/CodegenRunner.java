package wqs.codegen;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Strategy;
import org.jooq.meta.jaxb.Target;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Generates jOOQ classes from the Flyway-migrated schema inside a throwaway Postgres
 * container, so generated code can never drift from the migrations (spec section 8.3).
 *
 * Usage: CodegenRunner <output-directory>
 */
public final class CodegenRunner {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: CodegenRunner <output-directory>");
        }
        String outputDirectory = args[0];

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();

            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(postgres.getJdbcUrl());
            ds.setUser(postgres.getUsername());
            ds.setPassword(postgres.getPassword());

            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            GenerationTool.generate(configuration(postgres, outputDirectory));
        }
    }

    private static Configuration configuration(PostgreSQLContainer<?> postgres, String outputDirectory) {
        return new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(postgres.getJdbcUrl())
                        .withUser(postgres.getUsername())
                        .withPassword(postgres.getPassword()))
                .withGenerator(new Generator()
                        .withStrategy(new Strategy()
                                .withName("wqs.codegen.AsInDatabaseStrategy"))
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                // Partition children are tables. Without this the generated
                                // source grows by two classes every month, forever.
                                .withExcludes(
                                        ".*_\\d{4}_\\d{2}"
                                        + "|flyway_schema_history"
                                        + "|QRTZ_.*"))
                        .withGenerate(new Generate()
                                .withPojos(false)
                                .withDaos(false)
                                .withJavaTimeTypes(true)
                                .withRecords(true))
                        .withTarget(new Target()
                                .withPackageName("wqs.db.generated")
                                .withDirectory(outputDirectory)));
    }

    private CodegenRunner() {}
}
