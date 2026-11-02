package wqs.ingest.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.stream.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The real broker connection. Imported ONLY through {@link StreamConfig}'s deferred selector, and
 * deliberately not annotated {@code @Configuration}/{@code @Component}: a component scan or a
 * member-class pass would register it early, and {@code @ConditionalOnMissingBean} is only
 * reliable once every other configuration class (a test's fake {@link StreamSource}, say) has
 * been parsed. Deferred imports are processed last, like auto-configuration.
 */
class StreamBrokerConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(StreamSource.class)
    Environment streamEnvironment(StreamProperties properties) {
        return Environment.builder().uri(properties.uri()).build();
    }

    @Bean
    @ConditionalOnMissingBean(StreamSource.class)
    RabbitStreamSource rabbitStreamSource(
            Environment environment, StreamProperties properties, ObjectMapper objectMapper) {
        return new RabbitStreamSource(environment, properties, objectMapper);
    }
}
