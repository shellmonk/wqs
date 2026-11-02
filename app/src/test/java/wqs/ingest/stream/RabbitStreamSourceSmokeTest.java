package wqs.ingest.stream;

import wqs.projection.source.StreamPayload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Verifies that a message published to a real stream reaches a handler through
 * {@link RabbitStreamSource}, with unenumerated fields preserved in {@code extra}.
 *
 * The stream protocol advertises a host and port that the client must be able to reach, which
 * Testcontainers' random port mapping breaks -- hence the pinned 5552 and advertised_host.
 * Tagged so it can be excluded when that port is occupied:
 *   ./gradlew :app:test -PexcludeTags=broker
 */
@Tag("broker")
class RabbitStreamSourceSmokeTest {

    @Test
    void a_published_message_reaches_a_subscriber() throws Exception {
        try (GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:3.13-management")
                .withCopyToContainer(
                        Transferable.of("[rabbitmq_management,rabbitmq_stream]."),
                        "/etc/rabbitmq/enabled_plugins")
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS",
                        "-rabbitmq_stream advertised_host localhost")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(5552),
                                new ExposedPort(5552))))
                .withExposedPorts(5552)
                .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))) {

            rabbit.start();

            try (Environment environment = Environment.builder()
                    .host("localhost").port(5552).build()) {

                environment.streamCreator().stream("smoke").create();

                StreamProperties properties = new StreamProperties(true, "smoke", 10, null, 3, null, null);
                RabbitStreamSource source =
                        new RabbitStreamSource(environment, properties, new ObjectMapper());
                List<StreamPayload> payloads = new CopyOnWriteArrayList<>();
                List<Long> offsets = new CopyOnWriteArrayList<>();
                source.subscribe(0, new StreamSource.StreamHandler() {
                    @Override
                    public void onMessage(StreamPayload payload, long offset) {
                        payloads.add(payload);
                        offsets.add(offset);
                    }

                    @Override
                    public void onUndeserializable(byte[] body, long offset, Exception error) {
                        throw new AssertionError("unexpected poison", error);
                    }
                });

                String json = "{\"transactionId\":\"tx-smoke\",\"userId\":7,"
                        + "\"occurredAt\":\"2033-09-02T12:00:00Z\",\"type\":1,"
                        + "\"amount\":5.0000,\"currency\":\"EUR\",\"channel\":\"web\"}";
                try (Producer producer = environment.producerBuilder().stream("smoke").build()) {
                    producer.send(
                            producer.messageBuilder()
                                    .addData(json.getBytes(StandardCharsets.UTF_8)).build(),
                            status -> {});

                    await().atMost(Duration.ofSeconds(15)).until(() -> !payloads.isEmpty());
                }
                source.close();

                StreamPayload received = payloads.get(0);
                assertThat(received.transactionId()).isEqualTo("tx-smoke");
                assertThat(received.amount()).isEqualTo(new BigDecimal("5.0000"));
                assertThat(offsets.get(0)).isZero();
                assertThat(received.extra()).containsEntry("channel", "web");
                // I5: the head offset for wqs.ingest.stream.offset.lag comes from the broker.
                assertThat(source.headOffset()).hasValue(0L);
            }
        }
    }
}
