package wqs.config;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.EnvironmentBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The RabbitMQ stream {@link Environment} (one connection pool to the broker), only when enabled. */
@Configuration
@ConditionalOnProperty(name = "wqs.stream.enabled", havingValue = "true")
public class StreamConfig {

    @Bean(destroyMethod = "close")
    public Environment streamEnvironment(StreamProperties properties) {
        EnvironmentBuilder builder = Environment.builder().uri(properties.uri());
        if (properties.connectHost() != null && !properties.connectHost().isBlank()) {
            String host = properties.connectHost().strip();
            // Stream clients connect to the address the broker advertises; keep its port, replace its host.
            builder.addressResolver(advertised -> new Address(host, advertised.port()));
        }
        return builder.build();
    }
}
