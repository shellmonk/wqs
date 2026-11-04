package wqs.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import wqs.config.StreamProperties;
import wqs.service.StreamIngestService;

/**
 * Only the replica holding the advisory lock consumes. A second consumer (a "replica") built by
 * hand on the same stream stays on standby, and takes over from the stored offset when the leader goes.
 */
class StreamLeadershipTest extends StreamTestBase {

    @Autowired StreamConsumer leader;
    @Autowired StreamProperties properties;
    @Autowired StreamMessageDecoder decoder;
    @Autowired StreamIngestService ingest;
    @Autowired @Qualifier("writerDataSource") DataSource writer;
    @Autowired MeterRegistry meters;
    @Autowired TestRestTemplate http;

    private StreamConsumer replica;

    @BeforeEach
    void setUp() {
        await().atMost(Duration.ofSeconds(20)).until(() -> leader.state() == StreamConsumer.State.CONSUMING);
        replica = new StreamConsumer(streams, properties, decoder, ingest, new StreamLeadership(writer, properties),
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        replica.stop();
        if (!leader.isRunning()) {
            leader.start();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> leader.state() == StreamConsumer.State.CONSUMING);
    }

    @Test
    void a_second_replica_stands_by_while_the_leader_consumes() throws Exception {
        replica.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> replica.state() == StreamConsumer.State.STANDBY);

        byte[] poison = ("standby " + nextId()).getBytes(StandardCharsets.UTF_8);
        publish(poison);
        String id = nextId();
        publish(transfer(id));
        awaitRow(id);

        assertThat(replica.state()).isEqualTo(StreamConsumer.State.STANDBY);
        assertThat(deadLettersFor(poison)).isEqualTo(1); // handled once, by the leader only
        assertThat(meters.get("wqs.ingest.stream.leader").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void the_standby_takes_over_from_the_stored_offset_when_the_leader_stops() throws Exception {
        replica.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> replica.state() == StreamConsumer.State.STANDBY);
        byte[] poison = ("handover " + nextId()).getBytes(StandardCharsets.UTF_8);
        publish(poison);
        String before = nextId();
        publish(transfer(before));
        awaitRow(before);

        leader.stop();
        await().atMost(Duration.ofSeconds(20)).until(() -> replica.state() == StreamConsumer.State.CONSUMING);
        String after = nextId();
        publish(transfer(after));
        awaitRow(after);

        assertThat(deadLettersFor(poison)).as("nothing read twice after the handover").isEqualTo(1);
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
        String id = nextId();
        publish(transfer(id));
        awaitRow(id);
    }

    @Test
    void health_shows_the_consumer() {
        String body = http.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"streamConsumer\":{\"status\":\"UP\"");
    }
}
