package wqs.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.IntegrationTest;
import wqs.util.Hashes;

/**
 * Publishing to the real stream and waiting for the result in Postgres. Tests share one stream
 * and one consumer, so each uses its own ids ({@link #nextId()}) and waits on its own rows.
 */
abstract class StreamTestBase extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000);
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired Environment streams;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    private Producer producer;
    private final List<CountDownLatch> pending = new ArrayList<>();

    @BeforeEach
    void openProducer() {
        producer = streams.producerBuilder().stream(STREAM).build();
    }

    @AfterEach
    void closeProducer() {
        producer.close();
    }

    static String nextId() {
        return Long.toString(IDS.incrementAndGet());
    }

    /** A captured message (src/test/resources/stream/{fixture}) with its id, owner and time replaced. */
    byte[] message(String fixture, String id, String ownerId, String created) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(StreamMessageDecoderTest.fixture(fixture));
        node.put("id", id);
        ((ObjectNode) node.get("userContext")).put("ownerId", ownerId);
        ((ObjectNode) node.get("createdContext")).put("created", Instant.parse(created).toString());
        return mapper.writeValueAsBytes(node);
    }

    /** As above, with its type, amount and currency replaced too. */
    byte[] message(String fixture, String id, String ownerId, String created,
                   String type, int amount, String currency) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(message(fixture, id, ownerId, created));
        node.put("type", type);
        ((ObjectNode) node.get("amount")).put("amount", amount).put("currency", currency);
        return mapper.writeValueAsBytes(node);
    }

    /** A transfer without a session, owned by {@code "1-" + id}. */
    byte[] transfer(String id) throws Exception {
        return message("transfer-out.json", id, "1-" + id, "2026-09-10T00:00:00Z");
    }

    /** Publishes and waits for the broker's confirm. */
    void publish(byte[] body) throws InterruptedException {
        send(body);
        flush();
    }

    /** Publishes without waiting, so messages arrive together; {@link #flush()} waits for the confirms. */
    void send(byte[] body) {
        CountDownLatch confirmed = new CountDownLatch(1);
        pending.add(confirmed);
        producer.send(producer.messageBuilder().addData(body).build(), status -> {
            if (status.isConfirmed()) {
                confirmed.countDown();
            }
        });
    }

    void flush() throws InterruptedException {
        for (CountDownLatch latch : pending) {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("broker confirmed").isTrue();
        }
        pending.clear();
    }

    void awaitRow(String sourceId) {
        await().atMost(WAIT).until(() -> rows(sourceId) == 1);
    }

    long rows(String sourceId) {
        return jdbc.sql("select count(*) from \"wqPrjTransaction\" where \"sourceId\" = :id")
                .param("id", sourceId).query(Long.class).single();
    }

    /** Dead letters of an unreadable message, found by its body's hash. */
    long deadLettersFor(byte[] body) {
        return jdbc.sql("select count(*) from \"wqDeadLetter\" where \"bodySha256\" = :sha")
                .param("sha", Hashes.sha256(body)).query(Long.class).single();
    }
}
