package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PostgresTestBase;

/** wqs.stream.enabled defaults to false: broker-less contexts must start with no stream beans. */
@SpringBootTest
class StreamDisabledWiringTest extends PostgresTestBase {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ApplicationContext context;

    @Test
    void no_stream_consumer_environment_or_lifecycle_exists_by_default() {
        assertThat(context.getBeanNamesForType(StreamConsumer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StreamLifecycle.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.rabbitmq.stream.Environment.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StreamSource.class)).isEmpty();
    }

    /** R101: a replica with the consumer off says so at startup. */
    @Test
    void a_disabled_stream_registers_the_startup_warning() {
        assertThat(context.getBeanNamesForType(StreamDisabledNotice.class)).hasSize(1);
    }
}
