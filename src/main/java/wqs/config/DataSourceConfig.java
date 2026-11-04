package wqs.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two connection pools. The writer is primary: Flyway, Quartz, ingest and every unqualified
 * {@code JdbcClient}, {@code @Transactional} or {@code TransactionTemplate} use it. The reader
 * serves the API through {@link ReaderJdbc} inside a {@link ReaderTx} transaction.
 *
 * <p>Declaring the reader beans switches off Boot's own DataSource, transaction-manager,
 * {@code TransactionTemplate} and {@code JdbcClient} auto-configuration, so the writer ones are
 * declared here too.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("writer");
        return ds;
    }

    /** url/username/password fall back to {@code spring.datasource.*} when {@code wqs.datasource.reader.*} is unset. */
    @Bean
    public HikariDataSource readerDataSource(DataSourceProperties writer, Environment env) {
        Binder binder = Binder.get(env);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(binder.bind("wqs.datasource.reader.url", String.class).orElse(writer.determineUrl()));
        ds.setUsername(binder.bind("wqs.datasource.reader.username", String.class).orElse(writer.determineUsername()));
        ds.setPassword(binder.bind("wqs.datasource.reader.password", String.class).orElse(writer.determinePassword()));
        binder.bind("wqs.datasource.reader.hikari", Bindable.ofInstance(ds));
        // Set after binding so configuration cannot weaken them. readOnlyMode=always makes even
        // autocommit statements read-only, not just statements inside a read-only transaction.
        ds.setPoolName("reader");
        ds.setReadOnly(true);
        ds.addDataSourceProperty("readOnlyMode", "always");
        ds.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        return ds;
    }

    @Bean
    @Primary
    public JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @Primary
    public JdbcClient jdbcClient(NamedParameterJdbcTemplate jdbcTemplate) {
        return JdbcClient.create(jdbcTemplate);
    }

    @Bean
    public JdbcTransactionManager readerTransactionManager(@Qualifier("readerDataSource") DataSource readerDataSource) {
        return new JdbcTransactionManager(readerDataSource);
    }

    @Bean
    @ReaderJdbc
    public JdbcClient readerJdbc(@Qualifier("readerDataSource") DataSource readerDataSource) {
        return JdbcClient.create(readerDataSource);
    }

    @Bean
    @ReaderTx
    public TransactionTemplate readerTx(@Qualifier("readerTransactionManager") PlatformTransactionManager readerTransactionManager) {
        TransactionTemplate tx = new TransactionTemplate(readerTransactionManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }
}
