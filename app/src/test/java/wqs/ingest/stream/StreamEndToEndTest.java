package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import wqs.db.PostgresTestBase;

/**
 * Broker -> real RabbitStreamSource -> StreamConsumer -> projection tables -> /cc read: the whole
 * write/read loop with no fake source. Container setup mirrors RabbitStreamSourceSmokeTest (fixed
 * port 5552 because the stream protocol advertises host and port).
 *
 * Offset assertion: the stream is created fresh and unique to this class, so the first message is
 * offset 0 and the 4th is offset 3. The producer confirmation does not expose the offset, so the
 * test asserts the stored offset equals 3.
 *
 * The stream protocol has no claimable-prize wire type: StreamPayloadMapper always maps to
 * TRANSACTION, so the three valid events are transactions (two in one session, one outside).
 */
@Tag("broker")
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamEndToEndTest extends PostgresTestBase {

    private static final String STREAM = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
    private static final long USER = 9101L;
    private static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management")
            .withCopyToContainer(
                    Transferable.of("[rabbitmq_management,rabbitmq_stream]."),
                    "/etc/rabbitmq/enabled_plugins")
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS",
                    "-rabbitmq_stream advertised_host localhost")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(5552),
                            new ExposedPort(5552))))
            .withExposedPorts(5552)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static Environment environment;
    private static Producer producer;

    @BeforeAll
    static void startBroker() {
        RABBIT.start();
        environment = Environment.builder().host("localhost").port(5552).build();
        environment.streamCreator().stream(STREAM).create();
        producer = environment.producerBuilder().stream(STREAM).build();
        // Before the context starts consuming: no stale offset or rows from other classes.
        clearProjection();
    }

    @AfterAll
    static void stopBroker() {
        if (producer != null) {
            producer.close();
        }
        if (environment != null) {
            environment.close();
        }
        RABBIT.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wqs.stream.enabled", () -> "true");
        registry.add("wqs.stream.name", () -> STREAM);
        registry.add("wqs.stream.uri", () -> "rabbitmq-stream://localhost:5552");
    }

    @Autowired private StreamLifecycle lifecycle;
    @Autowired private MockMvc mvc;
    @Autowired private MeterRegistry registry;

    private static void publish(String body) throws InterruptedException {
        CountDownLatch confirmed = new CountDownLatch(1);
        producer.send(
                producer.messageBuilder().addData(body.getBytes(StandardCharsets.UTF_8)).build(),
                status -> confirmed.countDown());
        assertThat(confirmed.await(10, TimeUnit.SECONDS)).as("broker confirm").isTrue();
    }

    private static String event(String id, String at, int type, String amount, String batch) {
        return "{\"transactionId\":\"" + id + "\",\"userId\":" + USER + ",\"occurredAt\":\"" + at
                + "\",\"productId\":11,"
                + (batch == null ? "" : "\"batchId\":\"" + batch + "\",")
                + "\"type\":" + type + ",\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    }

    private static long count(String sql) {
        return dsl().fetchOne(sql).get(0, Long.class);
    }

    @Test
    @Order(1)
    void published_events_reach_tables_and_offset() throws Exception {
        publish(event("e2e-1", "2033-09-02T12:00:00Z", 1, "5.0000", "e2e-s1"));
        publish(event("e2e-2", "2033-09-02T12:05:00Z", 2, "8.0000", "e2e-s1"));
        publish("not json");
        publish(event("e2e-3", "2033-09-03T09:00:00Z", 1, "2.0000", null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(count("select count(*) from \"wqWebItem\" where \"userId\" = " + USER))
                    .isEqualTo(3);
            assertThat(count("select count(*) from \"wqSession\" where \"userId\" = " + USER))
                    .isEqualTo(1);
            assertThat(count("select count(*) from \"wqDeadLetter\"")).isEqualTo(1);
            assertThat(count("select \"offsetVal\" from \"wqStreamOffset\" where \"stream\" = '"
                    + STREAM + "'")).isEqualTo(3);
        });

        mvc.perform(get("/cc/v1/projection").param("userId", String.valueOf(USER))
                        .param("from", "2033-01-01T00:00:00Z").param("to", "2034-01-01T00:00:00Z")
                        .param("aggregationLevel", "0").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    @Order(2)
    void restart_resumes_after_the_stored_offset() throws Exception {
        double duplicatesBefore = duplicates();
        lifecycle.stop();
        publish(event("e2e-4", "2033-09-04T10:00:00Z", 1, "1.0000", null));
        lifecycle.start();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("select count(*) from \"wqWebItem\" where \"userId\" = " + USER))
                        .isEqualTo(4));
        // No replay duplicates: every sourceId appears exactly once; dead letters unchanged.
        assertThat(count("select count(distinct \"sourceId\") from \"wqWebItem\" where \"userId\" = "
                + USER)).isEqualTo(4);
        assertThat(count("select count(*) from \"wqDeadLetter\"")).isEqualTo(1);
        // The 5th message is offset 4. Resuming AT the stored offset (3) instead of after it would
        // replay offset 3 as an idempotent duplicate: rows and offset alone cannot see that.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("select \"offsetVal\" from \"wqStreamOffset\" where \"stream\" = '"
                        + STREAM + "'")).isEqualTo(4));
        assertThat(duplicates()).as("replayed duplicates across the restart").isEqualTo(duplicatesBefore);
    }

    private double duplicates() {
        return registry.get("wqs.ingest.events").tag("source", "stream").tag("outcome", "duplicate")
                .counter().count();
    }
}
