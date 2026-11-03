package wqs.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Separate writer and reader pools (spec 10, ruling D11). Defining any DataSource bean switches
 * off Boot's pooled DataSource auto-config, so both are explicit. The writer stays primary:
 * Flyway, Quartz, the transaction manager, the primary {@link JdbcClient}, ingest and
 * projection all use it. The reader serves only {@code wqs.read..}, through {@link ReaderJdbc}
 * inside a {@link ReaderTx} transaction.
 *
 * <p>Two transaction managers exist, so the writer one is {@code @Primary}: an unqualified
 * {@code @Transactional}, {@code PlatformTransactionManager} or {@code TransactionTemplate} is the
 * writer's. Declaring the reader {@code JdbcClient}, transaction manager and template switches off
 * Boot's {@code JdbcClient}, transaction-manager and {@code TransactionTemplate} auto-config
 * ({@code @ConditionalOnMissingBean}), so the writer ones are re-declared here exactly as Boot
 * builds them.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("writer");
        return ds;
    }

    @Bean
    public HikariDataSource readerDataSource(DataSourceProperties writer, Environment env) {
        Binder binder = Binder.get(env);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(binder.bind("wqs.datasource.reader.url", String.class).orElse(writer.determineUrl()));
        ds.setUsername(binder.bind("wqs.datasource.reader.username", String.class)
                .orElse(writer.determineUsername()));
        ds.setPassword(binder.bind("wqs.datasource.reader.password", String.class)
                .orElse(writer.determinePassword()));
        binder.bind("wqs.datasource.reader.hikari", Bindable.ofInstance(ds));
        // Set in code, after binding, so configuration cannot weaken them: P-3 within a request
        // relies on one repeatable-read snapshot, and the pool must be unable to write.
        ds.setPoolName("reader");
        ds.setReadOnly(true);
        ds.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        return ds;
    }

    /** As Boot's {@code DataSourceTransactionManagerAutoConfiguration} builds it, over the writer. */
    @Bean
    @Primary
    public DataSourceTransactionManager transactionManager(
            Environment environment, DataSource dataSource,
            ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers) {
        DataSourceTransactionManager tm =
                environment.getProperty("spring.dao.exceptiontranslation.enabled", Boolean.class, Boolean.TRUE)
                        ? new JdbcTransactionManager(dataSource)
                        : new DataSourceTransactionManager(dataSource);
        transactionManagerCustomizers.ifAvailable(customizers -> customizers.customize(tm));
        return tm;
    }

    /** As Boot's {@code TransactionAutoConfiguration} builds it, over the writer manager. */
    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** As Boot's {@code JdbcClientAutoConfiguration} builds it, over the writer's JdbcTemplate. */
    @Bean
    @Primary
    public JdbcClient jdbcClient(NamedParameterJdbcTemplate jdbcTemplate) {
        return JdbcClient.create(jdbcTemplate);
    }

    @Bean
    public DataSourceTransactionManager readerTransactionManager(
            @Qualifier("readerDataSource") DataSource readerDataSource) {
        return new JdbcTransactionManager(readerDataSource);
    }

    /**
     * Statements join the {@link ReaderTx} transaction they run in. Used outside one, each
     * statement autocommits in its own snapshot; ArchitectureTest requires every {@code wqs.read..}
     * class that injects this client to inject {@link ReaderTx} too, and ProjectionReader runs
     * every read inside one {@code readerTx.execute(..)}.
     */
    @Bean
    @ReaderJdbc
    public JdbcClient readerJdbc(@Qualifier("readerDataSource") DataSource readerDataSource) {
        return JdbcClient.create(readerDataSource);
    }

    /**
     * The read path's ONE transaction: read-only, REPEATABLE READ, on the reader pool. The pool already pins both (see
     * {@link #readerDataSource}); stating them here keeps them if the pool ever stops doing so.
     */
    @Bean
    @ReaderTx
    public TransactionTemplate readerTx(
            @Qualifier("readerTransactionManager") PlatformTransactionManager readerTransactionManager) {
        TransactionTemplate tx = new TransactionTemplate(readerTransactionManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }
}
