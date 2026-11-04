package wqs.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.quartz.QuartzDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Two connection pools behind one routing {@link DataSource}. A {@code @Transactional(readOnly = true)}
 * service method runs on the reader pool; everything else runs on the writer. Flyway and Quartz
 * always use the writer.
 *
 * <p>The routing proxy is lazy: the physical connection is taken at the first statement, after
 * the transaction manager has marked the transaction read-only or not, so the choice is made
 * per transaction.
 */
@Configuration
public class DataSourceConfig {

    enum Pool { WRITER, READER }

    @Bean
    @FlywayDataSource
    @QuartzDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource writerDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("writer");
        ds.addDataSourceProperty("ApplicationName", "wqs-writer");
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
        ds.addDataSourceProperty("ApplicationName", "wqs-reader");
        return ds;
    }

    /** The application's DataSource: JPA, JdbcClient and the transaction manager all use this one. */
    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("writerDataSource") DataSource writer,
                                 @Qualifier("readerDataSource") DataSource reader) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? Pool.READER : Pool.WRITER;
            }
        };
        routing.setTargetDataSources(Map.of(Pool.WRITER, writer, Pool.READER, reader));
        routing.setDefaultTargetDataSource(writer);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }
}
