package wqs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PostgresTestBase;

@SpringBootTest
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
    @Autowired @ReaderDsl DSLContext readerDsl;
    @Autowired DSLContext writerDsl;
    @Autowired MeterRegistry meterRegistry;

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
    void writer_dsl_uses_spring_transactions_and_the_reader_does_not() {
        assertThat(writerDsl.configuration().transactionProvider())
                .isInstanceOf(org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider.class);
        assertThat(readerDsl.configuration().transactionProvider())
                .isNotInstanceOf(org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider.class);
    }

    @Test
    void reader_dsl_rejects_writes() {
        assertThatThrownBy(() -> readerDsl.transaction(cfg -> cfg.dsl()
                        .insertInto(wqs.db.generated.Tables.WQ_STREAM_OFFSET)
                        .set(wqs.db.generated.Tables.WQ_STREAM_OFFSET.stream, "s")
                        .set(wqs.db.generated.Tables.WQ_STREAM_OFFSET.offsetVal, 1L)
                        .execute()))
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
