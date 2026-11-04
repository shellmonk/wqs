package wqs.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RabbitMQ stream connection. Off by default, so contexts without a broker (tests, tooling)
 * start; deployments set {@code WQS_STREAM_ENABLED=true}.
 *
 * @param batchSize    messages committed together in one transaction, at most
 * @param batchMaxWait how long a batch waits to fill once its first message has arrived
 */
@Validated
@ConfigurationProperties("wqs.stream")
public record StreamProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("rabbitmq-stream://localhost:5552") String uri,
        @NotBlank @DefaultValue("wallet-transactions") String name,
        @Min(1) @Max(10_000) @DefaultValue("500") int batchSize,
        @NotNull @DefaultValue("200ms") Duration batchMaxWait) {}
