package wqs.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RabbitMQ stream connection. Off by default, so contexts without a broker (tests, tooling)
 * start; deployments set {@code WQS_STREAM_ENABLED=true}.
 */
@Validated
@ConfigurationProperties("wqs.stream")
public record StreamProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("rabbitmq-stream://localhost:5552") String uri,
        @NotBlank @DefaultValue("wallet-transactions") String name) {}
