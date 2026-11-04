package wqs.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.IntegrationTest;
import wqs.config.StreamProperties;
import wqs.service.StreamIngestService;
import wqs.util.Hashes;

/**
 * Only the replica holding the advisory lock consumes. A second consumer (a "replica") built by
 * hand on the same stream stays on standby, and takes over from the stored offset when the leader goes.
 */
class StreamLeadershipTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000 + 500_000_000L);

    @Autowired StreamConsumer leader;
    @Autowired StreamProperties properties;
    @Autowired Environment streams;
    @Autowired StreamMessageDecoder decoder;
    @Autowired StreamIngestService ingest;
    @Autowired @Qualifier("writerDataSource") DataSource writer;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meters;
    @Autowired TestRestTemplate http;

    private StreamConsumer replica;
    private Producer producer;

    @BeforeEach
    void setUp() {
        await().atMost(Duration.ofSeconds(20)).until(() -> leader.state() == StreamConsumer.State.CONSUMING);
        replica = new StreamConsumer(streams, properties, decoder, ingest, new StreamLeadership(writer, properties),
                new SimpleMeterRegistry());
        producer = streams.producerBuilder().stream(STREAM).build();
    }

    @AfterEach
    void tearDown() {
        replica.stop();
        producer.close();
        if (!leader.isRunning()) {
            leader.start();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> leader.state() == StreamConsumer.State.CONSUMING);
    }

    @Test
    void a_second_replica_stands_by_while_the_leader_consumes() throws Exception {
        replica.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> replica.state() == StreamConsumer.State.STANDBY);

        byte[] poison = ("standby " + next()).getBytes(StandardCharsets.UTF_8);
        publish(poison);
        String id = next();
        publish(message(id));
        awaitRow(id);

        assertThat(replica.state()).isEqualTo(StreamConsumer.State.STANDBY);
        assertThat(deadLetters(poison)).isEqualTo(1); // handled once, by the leader only
        assertThat(meters.get("wqs.ingest.stream.leader").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void the_standby_takes_over_from_the_stored_offset_when_the_leader_stops() throws Exception {
        replica.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> replica.state() == StreamConsumer.State.STANDBY);
        byte[] poison = ("handover " + next()).getBytes(StandardCharsets.UTF_8);
        publish(poison);
        String before = next();
        publish(message(before));
        awaitRow(before);

        leader.stop();
        await().atMost(Duration.ofSeconds(20)).until(() -> replica.state() == StreamConsumer.State.CONSUMING);
        String after = next();
        publish(message(after));
        awaitRow(after);

        assertThat(deadLetters(poison)).as("nothing read twice after the handover").isEqualTo(1);
    }

    @Test
    void when_the_leaders_connection_dies_exactly_one_replica_consumes_again() throws Exception {
        replica.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> replica.state() == StreamConsumer.State.STANDBY);

        int killed = jdbc.sql("""
                select count(pg_terminate_backend(pid)) from pg_locks
                where locktype = 'advisory' and granted
                  and objid = (hashtextextended(:name, 0) & 4294967295)::oid
                """).param("name", "wqs-stream:" + properties.name()).query(Integer.class).single();
        assertThat(killed).isEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).until(() ->
                (leader.state() == StreamConsumer.State.CONSUMING) ^ (replica.state() == StreamConsumer.State.CONSUMING));
        String id = next();
        publish(message(id));
        awaitRow(id);
    }

    @Test
    void health_shows_the_consumer() {
        String body = http.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"streamConsumer\":{\"status\":\"UP\"");
    }

    // --- helpers ---

    private byte[] message(String id) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/stream/transfer-out.json"));
        node.put("id", id);
        ((ObjectNode) node.get("userContext")).put("ownerId", "1-" + id);
        ((ObjectNode) node.get("createdContext")).put("created", Instant.parse("2026-09-10T00:00:00Z").toString());
        return mapper.writeValueAsBytes(node);
    }

    private void publish(byte[] body) throws InterruptedException {
        CountDownLatch confirmed = new CountDownLatch(1);
        producer.send(producer.messageBuilder().addData(body).build(), status -> confirmed.countDown());
        assertThat(confirmed.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void awaitRow(String sourceId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("select count(*) from \"wqPrjTransaction\" where \"sourceId\" = :id")
                .param("id", sourceId).query(Long.class).single() == 1);
    }

    private long deadLetters(byte[] body) {
        return jdbc.sql("select count(*) from \"wqDeadLetter\" where \"bodySha256\" = :sha")
                .param("sha", Hashes.sha256(body)).query(Long.class).single();
    }

    private static String next() {
        return Long.toString(IDS.incrementAndGet());
    }
}
