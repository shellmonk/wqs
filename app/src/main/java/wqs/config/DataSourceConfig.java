package wqs.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * Separate writer and reader pools (spec 10, ruling D11). Defining any DataSource bean switches
 * off Boot's pooled DataSource auto-config, so both are explicit. The writer stays primary:
 * Flyway, Quartz, the transaction manager, Boot's jOOQ configuration, ingest and projection all
 * use it. The reader serves only {@code wqs.read..}, through {@link ReaderDsl}.
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

    /**
     * Boot's jOOQ auto-config is {@code @ConditionalOnMissingBean(DSLContext.class)}, so the reader
     * bean below would remove the writer DSLContext. Re-declaring it from Boot's still
     * auto-configured {@link org.jooq.Configuration} keeps Spring transactions and exception
     * translation. Do NOT register another {@code org.jooq.Configuration} bean.
     */
    @Bean
    @Primary
    public DSLContext dslContext(org.jooq.Configuration configuration) {
        return new DefaultDSLContext(configuration);
    }

    /** Plain jOOQ transactions: open them with {@code readerDsl.transactionResult(cfg -> ...)}. */
    @Bean
    @ReaderDsl
    public DSLContext readerDsl(@Qualifier("readerDataSource") DataSource readerDataSource) {
        return DSL.using(new DefaultConfiguration().set(readerDataSource).set(SQLDialect.POSTGRES));
    }
}
