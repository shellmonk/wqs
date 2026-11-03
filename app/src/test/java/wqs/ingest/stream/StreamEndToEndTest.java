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
 * Events use the real walAccountTransactions format (spec 4.1); one is the recorded PRIZE sample
 * with a non-zero amount. The three valid events are transactions (two in one session, one outside).
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

    /**
     * A real-format TT_BUY event (nested walAccountTransactions shape), optionally in a session.
     * An unsessioned one is a lottery ticket: an unsessioned eInstant item is dead-lettered (P-7).
     */
    private static String event(String id, String at, String type, String amount, String batch) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\","
                + "\"amount\":{\"amount\":" + amount + ",\"currency\":\"EUR\"},"
                + "\"productId\":\"TIKIKALACABANA\",\"productType\":\""
                + (batch == null ? "lottery" : "instants") + "\","
                + "\"userContext\":{\"ownerId\":\"1-" + USER + "\"},"
                + "\"batchContext\":{\"batchId\":\"" + (batch == null ? "" : batch) + "\"},"
                + "\"createdContext\":{\"created\":\"" + at + "\"}}";
    }

    /** The recorded PRIZE sample, with a non-zero amount (a zero-amount item is not projected), our user and an in-range date. */
    private static String prizeSample(String id, String batch, String amount) throws java.io.IOException {
        try (var in = StreamEndToEndTest.class.getResourceAsStream("/stream/prize-zero.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\"id\": \"5922\"", "\"id\": \"" + id + "\"")
                    .replace("\"amount\": 0,", "\"amount\": " + amount + ",")
                    .replace("\"ownerId\": \"1-1945\"", "\"ownerId\": \"1-" + USER + "\"")
                    .replace("GS-ur5z5sgfr5de5flhjd6ycryf6u", batch)
                    .replace("\"created\": \"2026-10-26T11:36:23.049062Z\"", "\"created\": \"2033-09-02T12:05:00Z\"");
        }
    }

    private static short typeId(String code) {
        return db().value("select \"typeId\" from \"wqTxType\" where \"code\" = '" + code + "'", Short.class);
    }

    private static long count(String sql) {
        return db().value(sql, Long.class);
    }

    @Test
    @Order(1)
    void published_events_reach_tables_and_offset() throws Exception {
        publish(event("e2e-1", "2033-09-02T12:00:00Z", "TT_BUY", "5.0000", "e2e-s1"));
        publish(prizeSample("e2e-2", "e2e-s1", "8"));
        publish("not json");
        publish(event("e2e-3", "2033-09-03T09:00:00Z", "TT_BUY", "2.0000", null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(count("select count(*) from \"wqWebItem\" where \"userId\" = " + USER))
                    .isEqualTo(3);
            assertThat(count("select count(*) from \"wqSession\" where \"userId\" = " + USER))
                    .isEqualTo(1);
            assertThat(count("select count(*) from \"wqDeadLetter\"")).isEqualTo(1);
            assertThat(count("select \"offsetVal\" from \"wqStreamOffset\" where \"stream\" = '"
                    + STREAM + "'")).isEqualTo(3);
        });

        // The real PRIZE sample landed as a row of the right type, product and session.
        var prize = db().row("select \"type\", \"productId\", \"productType\", \"batchId\", \"amount\""
                + " from \"wqWebItem\" where \"sourceId\" = 'e2e-2'");
        assertThat(prize.get("type", Short.class)).isEqualTo(typeId("PRIZE"));
        assertThat(prize.get("productId", String.class)).isEqualTo("TIKIKALACABANA");
        assertThat(prize.get("productType", String.class)).isEqualTo("instants");
        assertThat(prize.get("batchId", String.class)).isEqualTo("e2e-s1");
        assertThat(prize.get("amount", java.math.BigDecimal.class)).isEqualByComparingTo("8");

        mvc.perform(get("/cc/wallet-data/rest/v1/projection/{p}", USER)
                        .param("from", "2033-01-01T00:00:00Z").param("to", "2034-01-01T00:00:00Z")
                        .param("aggregationLevel", "0").param("pageNumber", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(3))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @Order(2)
    void restart_resumes_after_the_stored_offset() throws Exception {
        double duplicatesBefore = duplicates();
        lifecycle.stop();
        publish(event("e2e-4", "2033-09-04T10:00:00Z", "TT_BUY", "1.0000", null));
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
