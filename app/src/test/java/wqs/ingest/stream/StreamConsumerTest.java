package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.projection.ProjectionWriter;
import wqs.projection.StreamPayloadMapper;

@SpringBootTest
class StreamConsumerTest extends PostgresTestBase {

    private static final Instant WHEN = Instant.parse("2033-09-02T12:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private StreamOffsetStore offsetStore;
    @Autowired private StreamPayloadMapper payloadMapper;
    @Autowired private PartitionManager partitionManager;
    @Autowired private wqs.ingest.DeadLetterStore deadLetterStore;

    private FakeStreamSource source;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2033, 9), 0);
        clearProjection();
        dsl().execute("delete from \"wqStreamOffset\"");
        source = new FakeStreamSource();
        registry = new SimpleMeterRegistry();
    }

    @Test
    void projects_delivered_messages_and_advances_the_offset() {
        StreamConsumer consumer = consumer(2, Duration.ofMillis(200));
        consumer.start();

        source.queue(payload("tx-1"), 1);
        source.queue(payload("tx-2"), 2);
        source.deliverAll();
        consumer.poll();

        assertThat(count("wqWebItem")).isEqualTo(2);
        assertThat(offsetStore.read("wallet-transactions")).contains(2L);
    }

    @Test
    void resumes_from_the_stored_offset() {
        offsetStore.write("wallet-transactions", 17L);

        consumer(10, Duration.ofMillis(200)).start();

        // Resume from the NEXT offset, not the committed one, or the last event replays.
        assertThat(source.subscribedFrom()).isEqualTo(18L);
    }

    @Test
    void starts_from_zero_when_no_offset_is_stored() {
        consumer(10, Duration.ofMillis(200)).start();

        assertThat(source.subscribedFrom()).isZero();
    }

    @Test
    void publishes_ingest_lag_and_event_counts() {
        StreamConsumer consumer = consumer(1, Duration.ofMillis(200));
        consumer.start();

        source.queue(payload("tx-1"), 1);
        source.deliverAll();
        consumer.poll();

        // Fixed clock is WHEN+5s and the event occurred at WHEN.
        assertThat(registry.find("wqs.ingest.lag.seconds").gauge()).isNotNull();
        assertThat(registry.find("wqs.ingest.lag.seconds").gauge().value()).isEqualTo(5.0);
        assertThat(registry.find("wqs.ingest.events").tag("outcome", "projected").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void counts_duplicates_separately() {
        StreamConsumer consumer = consumer(1, Duration.ofMillis(200));
        consumer.start();

        source.queue(payload("tx-1"), 1);
        source.deliverAll();
        consumer.poll();
        source.queue(payload("tx-1"), 2);
        source.deliverAll();
        consumer.poll();

        assertThat(registry.find("wqs.ingest.events").tag("outcome", "duplicate").counter().count())
                .isEqualTo(1.0);
        assertThat(count("wqWebItem")).isEqualTo(1);
    }

    private StreamConsumer consumer(int batchSize, Duration maxWait) {
        StreamProperties properties =
                new StreamProperties(true, "wallet-transactions", batchSize, maxWait, 3, null, null);
        return new StreamConsumer(
                source, properties, payloadMapper, writer, offsetStore, registry,
                Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC), deadLetterStore);
    }

    private StreamPayload payload(String id) {
        return new StreamPayload(
                id, 7L, WHEN, 11, "b-1", "p-1", (short) 1,
                new BigDecimal("5.0000"), "EUR", null, null, java.util.Map.of());
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }
}
