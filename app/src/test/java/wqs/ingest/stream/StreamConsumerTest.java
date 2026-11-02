package wqs.ingest.stream;

import wqs.projection.source.StreamPayload;

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
    @Autowired private wqs.db.TxTypeRegistry txTypes;
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

    /** C1: an idle-from-start or stuck-from-start consumer must not report a reassuring 0. */
    @Test
    void lag_is_nan_before_start_and_time_since_start_until_something_is_projected() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = new StreamConsumer(
                source, new StreamProperties(true, "wallet-transactions", 10, Duration.ofMillis(200), 3, null, null),
                payloadMapper, txTypes, writer, offsetStore, registry, clock, deadLetterStore);
        var gauge = registry.find("wqs.ingest.lag.seconds").gauge();
        assertThat(gauge.value()).isNaN();

        consumer.start();
        clock.advance(Duration.ofSeconds(90));

        assertThat(gauge.value()).isEqualTo(90.0);
    }

    /** I5: head minus committed; NaN when the source cannot report a head. */
    @Test
    void publishes_stream_offset_lag_from_the_head_and_the_committed_offset() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        new StreamConsumer(
                source, new StreamProperties(true, "wallet-transactions", 10, Duration.ofMillis(200), 3, null, null),
                payloadMapper, txTypes, writer, offsetStore, registry, clock, deadLetterStore);
        var gauge = registry.find("wqs.ingest.stream.offset.lag").gauge();
        assertThat(gauge.value()).isNaN(); // fake source: head unknown

        source.head(120L);
        offsetStore.write("wallet-transactions", 100L);
        clock.advance(Duration.ofSeconds(16)); // past the cache
        assertThat(gauge.value()).isEqualTo(20.0);

        offsetStore.write("wallet-transactions", 125L); // committed past the chunk-id head
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge.value()).isZero();
    }

    @Test
    void records_batch_size_and_duration() {
        StreamConsumer consumer = consumer(2, Duration.ofMillis(200));
        consumer.start();

        source.queue(payload("tx-1"), 1);
        source.queue(payload("tx-2"), 2);
        source.deliverAll();

        var size = registry.find("wqs.ingest.batch.size").summary();
        assertThat(size.count()).isEqualTo(1);
        assertThat(size.totalAmount()).isEqualTo(2.0);
        assertThat(registry.find("wqs.ingest.batch.duration").timer().count()).isEqualTo(1);
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

    @Test
    void start_survives_a_failing_subscribe_and_poll_retries() {
        offsetStore.write("wallet-transactions", 9L);
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumerWith(clock, 1);
        source.failNextSubscribes(2);

        consumer.start(); // first subscribe fails: must not throw
        assertThat(source.subscribeCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(6)); // past the default 5s backoff
        consumer.poll(); // second subscribe fails
        assertThat(source.subscribeCount()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(6));
        consumer.poll(); // third succeeds
        assertThat(source.subscribeCount()).isEqualTo(3);
        assertThat(source.subscribeOffsets()).containsExactly(10L, 10L, 10L);
        assertThat(source.subscribedFrom()).isEqualTo(10L);

        source.queue(payload("tx-10"), 10);
        source.deliverAll();
        consumer.poll();
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(offsetStore.read("wallet-transactions")).contains(10L);
    }

    @Test
    void start_does_not_resubscribe_while_backing_off() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumerWith(clock, 1);
        source.failNextSubscribes(1);

        consumer.start();
        clock.advance(Duration.ofSeconds(4)); // inside the 5s backoff
        consumer.poll();
        consumer.poll();

        assertThat(source.subscribeCount()).isEqualTo(1);
    }

    /** Convention: an Error from subscribe() sets recovery state first, then is rethrown. */
    @Test
    void an_error_from_subscribe_during_start_sets_recovery_state_then_rethrows() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumerWith(clock, 1);
        source.failNextSubscribes(1, new StackOverflowError("simulated"));

        org.junit.jupiter.api.Assertions.assertThrows(StackOverflowError.class, consumer::start);

        clock.advance(Duration.ofSeconds(6));
        consumer.poll(); // recovery state was set, so this re-subscribes
        assertThat(source.subscribeCount()).isEqualTo(2);
    }

    /**
     * The registry reaches the database on a first-sighted type code. A transient failure there is
     * infrastructure, not poison: no dead letter, no offset advance, and the message is applied
     * after recovery.
     */
    @Test
    void a_transient_registry_failure_recovers_instead_of_dead_lettering() {
        wqs.db.TxTypeRegistry flaky = org.mockito.Mockito.spy(txTypes);
        org.mockito.Mockito.doThrow(new org.springframework.dao.TransientDataAccessResourceException("blip"))
                .doCallRealMethod().when(flaky).idOf("TT_BUY");
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamProperties properties =
                new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null);
        StreamConsumer consumer = new StreamConsumer(
                source, properties, payloadMapper, flaky, writer, offsetStore, registry, clock, deadLetterStore);
        dsl().execute("delete from \"wqDeadLetter\"");
        consumer.start();

        source.queue(payload("tx-flaky"), 5);
        source.deliverAll(); // must not throw

        assertThat(count("wqDeadLetter")).as("not dead-lettered").isZero();
        assertThat(offsetStore.read("wallet-transactions")).as("offset not advanced").isEmpty();
        assertThat(count("wqWebItem")).isZero();

        clock.advance(Duration.ofSeconds(6));
        consumer.poll(); // backoff elapsed: re-subscribes from the stored offset
        source.redeliverFromSubscription();
        consumer.poll();

        assertThat(count("wqWebItem")).as("applied after recovery").isEqualTo(1);
        assertThat(offsetStore.read("wallet-transactions")).contains(5L);
        assertThat(count("wqDeadLetter")).isZero();
    }

    private StreamConsumer consumerWith(Clock clock, int batchSize) {
        StreamProperties properties =
                new StreamProperties(true, "wallet-transactions", batchSize, Duration.ofMillis(200), 3, null, null);
        return new StreamConsumer(
                source, properties, payloadMapper, txTypes, writer, offsetStore, registry, clock, deadLetterStore);
    }

    private StreamConsumer consumer(int batchSize, Duration maxWait) {
        StreamProperties properties =
                new StreamProperties(true, "wallet-transactions", batchSize, maxWait, 3, null, null);
        return new StreamConsumer(
                source, properties, payloadMapper, txTypes, writer, offsetStore, registry,
                Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC), deadLetterStore);
    }

    private StreamPayload payload(String id) {
        return new StreamPayload(id, 1, 7L, WHEN, "TT_BUY", "11", null, "b-1", "p-1", new BigDecimal("5.0000"), "EUR", null, null, java.util.Map.of());
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
