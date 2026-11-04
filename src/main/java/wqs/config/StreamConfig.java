package wqs.config;

import com.rabbitmq.stream.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The RabbitMQ stream {@link Environment} (one connection pool to the broker), only when enabled. */
@Configuration
@ConditionalOnProperty(name = "wqs.stream.enabled", havingValue = "true")
public class StreamConfig {

    @Bean(destroyMethod = "close")
    public Environment streamEnvironment(StreamProperties properties) {
        return Environment.builder().uri(properties.uri()).build();
    }
}
