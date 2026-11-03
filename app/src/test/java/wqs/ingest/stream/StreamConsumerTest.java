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
        db().execute("delete from \"wqStreamOffset\"");
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
        db().execute("delete from \"wqDeadLetter\"");
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

    /**
     * Security gate (spec 4.1, D8): owner "2-7" is NOT player 7. A non-player event writes nothing
     * at all -- no locator, item, rollup, page row or counter -- is counted, and the offset still
     * moves past it, alone or behind a buffered player event.
     */
    @Test
    void non_player_owner_is_skipped_and_counted() {
        StreamConsumer consumer = consumer(1, Duration.ofMillis(200));
        consumer.start();

        source.queue(nonPlayer("np-1"), 4);
        source.deliverAll();
        consumer.poll();

        for (String table : java.util.List.of("wqItemLocator", "wqWebItem", "wqCcItem", "wqGroupLocator",
                "wqSession", "wqPlay", "wqWebHighLevel", "wqCcHighLevel", "wqCountDay", "wqCountMonth",
                "wqPendingCancel", "wqDeadLetter")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(offsetStore.read("wallet-transactions")).contains(4L);
        assertThat(skipped("non-player")).isEqualTo(1.0);
        assertThat(skipped("zero-amount")).as("pre-registered").isZero();
    }

    @Test
    void a_non_player_event_behind_a_buffered_player_event_is_covered_by_the_batch_offset() {
        StreamConsumer consumer = consumer(10, Duration.ofMillis(200));
        consumer.start();

        source.queue(payload("tx-1"), 5);
        source.queue(nonPlayer("np-1"), 6);
        source.deliverAll();
        consumer.stop(); // flushes the buffered batch

        assertThat(count("wqItemLocator")).isEqualTo(1);
        assertThat(skipped("non-player")).isEqualTo(1.0);
        assertThat(offsetStore.read("wallet-transactions")).contains(6L);
    }

    @Test
    void a_zero_amount_event_is_counted_as_skipped() {
        StreamConsumer consumer = consumer(1, Duration.ofMillis(200));
        consumer.start();

        source.queue(new StreamPayload("z-1", 1, 7L, WHEN, "PRIZE", "P1", "instants", "b-1", "p-1",
                new BigDecimal("0.0000"), "EUR", null, null, java.util.Map.of()), 3);
        source.deliverAll();
        consumer.poll();

        assertThat(skipped("zero-amount")).isEqualTo(1.0);
        assertThat(count("wqItemLocator")).isEqualTo(1);
        assertThat(count("wqWebItem")).isZero();
        assertThat(offsetStore.read("wallet-transactions")).contains(3L);
    }

    private double skipped(String reason) {
        var counter = registry.find("wqs.ingest.skipped").tag("reason", reason).counter();
        assertThat(counter).as("wqs.ingest.skipped{reason=" + reason + "}").isNotNull();
        return counter.count();
    }

    private StreamPayload nonPlayer(String id) {
        return new StreamPayload(id, 2, 7L, WHEN, "TT_BUY", "11", null, "b-1", "p-1", new BigDecimal("5.0000"), "EUR", null, null, java.util.Map.of());
    }

    /**
     * F2b: a NON-transient database failure while mapping (here a constraint violation from the
     * registry's first-sighting insert) will fail the same way on every retry, so it is poison:
     * dead-lettered with the offset advanced, never a permanent stall.
     */
    @Test
    void a_non_transient_registry_failure_is_dead_lettered_and_the_offset_advances() {
        wqs.db.TxTypeRegistry broken = org.mockito.Mockito.spy(txTypes);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("value too long"))
                .when(broken).idOf("TT_BUY");
        StreamProperties properties =
                new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null);
        StreamConsumer consumer = new StreamConsumer(
                source, properties, payloadMapper, broken, writer, offsetStore, registry,
                Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC), deadLetterStore);
        consumer.start();

        source.queue(payload("tx-poison"), 6);
        source.deliverAll();

        assertThat(count("wqDeadLetter")).isEqualTo(1);
        assertThat(db().row("select \"sourceId\", \"streamOffset\" from \"wqDeadLetter\"").asMap().values().toArray())
                .containsExactly("tx-poison", 6L);
        assertThat(offsetStore.read("wallet-transactions")).contains(6L);
        assertThat(count("wqWebItem")).isZero();
        assertThat(source.subscribeCount()).as("no recovery re-subscribe").isEqualTo(1);
    }

    /**
     * F3: a connection failure (a pool timeout, a DB outage) is a NonTransientDataAccessException
     * in Spring's hierarchy, yet it is infrastructure: no dead letter, no offset advance, and the
     * message is applied after recovery. Only a data-integrity violation from mapping is poison.
     */
    @Test
    void a_connection_failure_in_the_registry_recovers_instead_of_dead_lettering() {
        wqs.db.TxTypeRegistry flaky = org.mockito.Mockito.spy(txTypes);
        org.mockito.Mockito.doThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("pool timeout"))
                .doCallRealMethod().when(flaky).idOf("TT_BUY");
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamProperties properties =
                new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null);
        StreamConsumer consumer = new StreamConsumer(
                source, properties, payloadMapper, flaky, writer, offsetStore, registry, clock, deadLetterStore);
        consumer.start();

        source.queue(payload("tx-outage"), 5);
        source.deliverAll();

        assertThat(count("wqDeadLetter")).as("not dead-lettered").isZero();
        assertThat(offsetStore.read("wallet-transactions")).as("offset not advanced").isEmpty();

        clock.advance(Duration.ofSeconds(6));
        consumer.poll();
        source.redeliverFromSubscription();
        consumer.poll();

        assertThat(count("wqWebItem")).as("applied after recovery").isEqualTo(1);
        assertThat(offsetStore.read("wallet-transactions")).contains(5L);
        assertThat(count("wqDeadLetter")).isZero();
    }

    /**
     * Final review I-C: a deadlock (40P01) or other lock conflict while applying an item alone is a
     * transient conflict with a concurrent writer (the delta poller, ruling I2), not poison: no dead
     * letter, no offset advance, and the item is applied after recovery.
     */
    @Test
    void a_lock_conflict_applying_an_item_alone_recovers_instead_of_dead_lettering() {
        for (RuntimeException conflict : java.util.List.<RuntimeException>of(
                new org.springframework.dao.CannotAcquireLockException("ERROR: deadlock detected (40P01)"),
                new org.springframework.dao.PessimisticLockingFailureException("ERROR: deadlock detected (40P01)"))) {
            clearProjection();
            source = new FakeStreamSource();
            // Fails every attempt the old code would make (3 as a batch, 3 alone), then succeeds.
            ProjectionWriter conflicted = failing(conflict, 6);
            MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
            StreamConsumer consumer = new StreamConsumer(source,
                    new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null),
                    payloadMapper, txTypes, conflicted, offsetStore, registry, clock, deadLetterStore);
            consumer.start();

            source.queue(payload("tx-locked"), 5);
            source.deliverAll();

            assertThat(count("wqDeadLetter")).as("%s is not poison", conflict).isZero();
            assertThat(offsetStore.read("wallet-transactions")).as("offset not advanced").isEmpty();

            for (int round = 0; round < 3 && count("wqWebItem") == 0; round++) {
                clock.advance(Duration.ofSeconds(6));
                consumer.poll();
                source.redeliverFromSubscription();
                consumer.poll();
            }

            assertThat(count("wqWebItem")).as("applied after recovery").isEqualTo(1);
            assertThat(offsetStore.read("wallet-transactions")).contains(5L);
            assertThat(count("wqDeadLetter")).isZero();
            consumer.stop();
        }
    }

    /** A data-integrity rejection applying an item alone is still poison: dead-lettered, offset advanced. */
    @Test
    void a_data_integrity_violation_applying_an_item_alone_is_dead_lettered() {
        ProjectionWriter rejecting = failing(new org.springframework.dao.DataIntegrityViolationException("bad"), 99);
        StreamConsumer consumer = new StreamConsumer(source,
                new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null),
                payloadMapper, txTypes, rejecting, offsetStore, registry,
                Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC), deadLetterStore);
        consumer.start();

        source.queue(payload("tx-rejected"), 7);
        source.deliverAll();

        assertThat(db().value("select \"sourceId\" from \"wqDeadLetter\"", String.class))
                .isEqualTo("tx-rejected");
        assertThat(offsetStore.read("wallet-transactions")).contains(7L);
        assertThat(count("wqWebItem")).isZero();
    }

    /**
     * Last fix T: an unknown bug (NPE, IllegalStateException) applying an item alone is not on the
     * transient allowlist: dead-lettered, offset advanced -- never a recovery loop stalling the stream.
     */
    @Test
    void a_bug_applying_an_item_alone_is_dead_lettered_and_the_offset_advances() {
        long offset = 20;
        for (RuntimeException bug : java.util.List.<RuntimeException>of(
                new NullPointerException("bug"), new IllegalStateException("bug"))) {
            clearProjection();
            source = new FakeStreamSource();
            StreamConsumer consumer = new StreamConsumer(source,
                    new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null),
                    payloadMapper, txTypes, failing(bug, 99), offsetStore, registry,
                    Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC), deadLetterStore);
            consumer.start();

            source.queue(payload("tx-bug"), offset);
            source.deliverAll();

            assertThat(count("wqDeadLetter")).as("%s", bug).isEqualTo(1);
            assertThat(offsetStore.read("wallet-transactions")).contains(offset);
            assertThat(source.subscribeCount()).as("no recovery re-subscribe").isEqualTo(1);
            offset++;
        }
    }

    /** Last fix T: a bug while MAPPING (not data, not infrastructure) is dead-lettered too. */
    @Test
    void a_bug_in_mapping_is_dead_lettered_and_the_offset_advances() {
        wqs.db.TxTypeRegistry buggy = org.mockito.Mockito.spy(txTypes);
        org.mockito.Mockito.doThrow(new NullPointerException("bug")).when(buggy).idOf("TT_BUY");
        StreamConsumer consumer = new StreamConsumer(source,
                new StreamProperties(true, "wallet-transactions", 1, Duration.ofMillis(200), 3, null, null),
                payloadMapper, buggy, writer, offsetStore, registry,
                Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC), deadLetterStore);
        consumer.start();

        source.queue(payload("tx-npe"), 8);
        source.deliverAll();

        assertThat(count("wqDeadLetter")).isEqualTo(1);
        assertThat(offsetStore.read("wallet-transactions")).contains(8L);
        assertThat(source.subscribeCount()).as("no recovery re-subscribe").isEqualTo(1);
    }

    /** The real writer, except that the first {@code times} calls throw {@code failure}. */
    private ProjectionWriter failing(RuntimeException failure, int times) {
        ProjectionWriter mock = org.mockito.Mockito.mock(ProjectionWriter.class);
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(times);
        org.mockito.Mockito.when(mock.apply(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            if (left.getAndDecrement() > 0) {
                throw failure;
            }
            return writer.apply(call.getArgument(0));
        });
        return mock;
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
        return db().value("select count(*)::int from \"" + table + "\"", Integer.class);
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
