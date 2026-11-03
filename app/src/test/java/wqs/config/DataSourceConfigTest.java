package wqs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PostgresTestBase;

@SpringBootTest
@Import(DataSourceConfigTest.MixedWrites.class)
class DataSourceConfigTest extends PostgresTestBase {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired DataSource dataSource;
    @Autowired @Qualifier("readerDataSource") DataSource readerDataSource;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcClient writerJdbc;
    @Autowired @ReaderJdbc JdbcClient readerJdbc;
    @Autowired @ReaderTx TransactionTemplate readerTx;
    @Autowired TransactionTemplate writerTx;
    @Autowired PlatformTransactionManager transactionManager;

    /** A writer {@code @Transactional} that writes twice through the primary JdbcClient. */
    @TestConfiguration
    static class MixedWrites {
        @Bean
        MixedWriter mixedWriter(JdbcClient jdbc) {
            return new MixedWriter(jdbc);
        }
    }

    static class MixedWriter {
        private final JdbcClient jdbc;

        MixedWriter(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        /** Writes two offset rows, checks both are visible on the transaction's connection, then fails. */
        @Transactional
        public void writeBothThenFail(String first, String second) {
            jdbc.sql("insert into \"wqStreamOffset\" (\"stream\", \"offsetVal\") values (?, 1)")
                    .param(first).update();
            jdbc.sql("insert into \"wqStreamOffset\" (\"stream\", \"offsetVal\") values (?, 2)")
                    .param(second).update();
            // Uncommitted rows are visible only on the connection that wrote them.
            assertThat(countOffsets(jdbc, first, second)).as("one connection sees both").isEqualTo(2);
            throw new IllegalStateException("roll back");
        }
    }

    @Autowired
    void wire(MixedWriter writer) {
        this.mixedWriter = writer;
    }

    private MixedWriter mixedWriter;

    private static long countOffsets(JdbcClient jdbc, String a, String b) {
        return jdbc.sql("select count(*) from \"wqStreamOffset\" where \"stream\" in (?, ?)")
                .params(a, b).query(Long.class).single();
    }

    @Test
    void writer_is_primary_and_named_writer() {
        assertThat(((HikariDataSource) dataSource).getPoolName()).isEqualTo("writer");
    }

    @Test
    void reader_pool_is_named_reader_read_only_repeatable_read() {
        HikariDataSource r = (HikariDataSource) readerDataSource;
        assertThat(r.getPoolName()).isEqualTo("reader");
        assertThat(r.isReadOnly()).isTrue();
        assertThat(r.getTransactionIsolation()).isEqualTo("TRANSACTION_REPEATABLE_READ");
        assertThat(r.getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void the_writer_transaction_manager_jdbc_client_and_template_are_primary() {
        assertThat(((DataSourceTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
        assertThat(writerTx.getTransactionManager()).isSameAs(transactionManager);
        assertThat(readerTx.getTransactionManager()).isNotSameAs(transactionManager);
        assertThat(((DataSourceTransactionManager) readerTx.getTransactionManager()).getDataSource())
                .isSameAs(readerDataSource);
        assertThat(readerTx.isReadOnly()).isTrue();
        assertThat(readerTx.getIsolationLevel())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(writerJdbc).isNotSameAs(readerJdbc);
    }

    /** Writer JdbcClient statements in one writer @Transactional share it; a rollback undoes both. */
    @Test
    void jdbc_client_writes_share_one_writer_transaction() {
        String first = "r1-first-" + UUID.randomUUID();
        String second = "r1-second-" + UUID.randomUUID();

        assertThatThrownBy(() -> mixedWriter.writeBothThenFail(first, second))
                .isInstanceOf(IllegalStateException.class).hasMessage("roll back");

        assertThat(countOffsets(writerJdbc, first, second)).as("both writes rolled back").isZero();
    }

    /** Reader side: every @ReaderJdbc statement in one @ReaderTx callback reads the same snapshot. */
    @Test
    void reader_jdbc_statements_share_one_reader_snapshot() {
        String stream = "r1-snapshot-" + UUID.randomUUID();
        try {
            long[] seen = readerTx.execute(status -> {
                long before = readerJdbc.sql("select count(*) from \"wqStreamOffset\" where \"stream\" = ?")
                        .param(stream).query(Long.class).single();
                // Committed by the writer pool, outside the reader transaction.
                writerJdbc.sql("insert into \"wqStreamOffset\" (\"stream\", \"offsetVal\") values (?, 1)")
                        .param(stream).update();
                long viaJdbc = readerJdbc.sql("select count(*) from \"wqStreamOffset\" where \"stream\" = ?")
                        .param(stream).query(Long.class).single();
                long again = readerJdbc.sql("select count(*) from \"wqStreamOffset\" where \"stream\" = ?")
                        .param(stream).query(Long.class).single();
                return new long[] {before, viaJdbc, again};
            });
            assertThat(seen).as("before, after the commit, once more").containsExactly(0, 0, 0);
            assertThat(readerJdbc.sql("select count(*) from \"wqStreamOffset\" where \"stream\" = ?")
                    .param(stream).query(Long.class).single()).as("a new transaction sees the row").isEqualTo(1);
        } finally {
            writerJdbc.sql("delete from \"wqStreamOffset\" where \"stream\" = ?").param(stream).update();
        }
    }

    @Test
    void reader_jdbc_rejects_writes_inside_the_reader_transaction() {
        assertThatThrownBy(() -> readerTx.executeWithoutResult(status -> readerJdbc
                        .sql("insert into \"wqStreamOffset\" (\"stream\", \"offsetVal\") values ('s', 1)")
                        .update()))
                .rootCause()
                .hasMessageContaining("read-only transaction");
    }

    @Test
    void hikari_metrics_are_tagged_by_pool() throws Exception {
        // The reader pool is lazy: its gauges exist only after the first connection.
        try (var c = readerDataSource.getConnection()) {
            assertThat(c.isReadOnly()).isTrue();
        }
        assertThat(meterRegistry.find("hikaricp.connections.max").tag("pool", "writer").gauge()).isNotNull();
        assertThat(meterRegistry.find("hikaricp.connections.max").tag("pool", "reader").gauge()).isNotNull();
    }
}
