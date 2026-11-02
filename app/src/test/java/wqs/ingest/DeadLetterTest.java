package wqs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.ingest.stream.FakeStreamSource;
import wqs.ingest.stream.StreamConsumer;
import wqs.ingest.stream.StreamOffsetStore;
import wqs.projection.source.StreamPayload;
import wqs.ingest.stream.StreamProperties;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionWriter;
import wqs.projection.StreamPayloadMapper;
import wqs.projection.WriteResult;

@SpringBootTest
class DeadLetterTest extends PostgresTestBase {

    private static final String STREAM = "wallet-transactions";
    private static final Instant WHEN = Instant.parse("2033-09-02T12:00:00Z");
    /** Beyond any partition sanity window (spec 5.8): projecting it always fails. */
    private static final Instant POISON_WHEN = Instant.parse("2099-01-01T00:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private DeadLetterStore deadLetterStore;
    @Autowired private StreamOffsetStore offsetStore;
    @Autowired private StreamPayloadMapper payloadMapper;
    @Autowired private wqs.db.TxTypeRegistry txTypes;
    @Autowired private ProjectionWriter writer;
    @Autowired private PartitionManager partitionManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private FakeStreamSource source;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2033, 9), 0);
        dsl().execute("delete from \"wqDeadLetter\"");
        dsl().execute("delete from \"wqStreamOffset\"");
        clearProjection();
        source = new FakeStreamSource();
        registry = new SimpleMeterRegistry();
    }

    @Test
    void a_poison_event_is_recorded_and_the_offset_advances() {
        // 2099 is beyond the partition sanity window, so projecting this always fails.
        StreamConsumer consumer = consumer(1, writer, deadLetterStore, fixedClock());
        consumer.start();

        source.queue(payload("tx-poison", POISON_WHEN), 5);
        source.deliverAll();
        consumer.poll();

        assertThat(count()).isEqualTo(1);
        // Ingestion must keep flowing past the bad event.
        assertThat(offsetStore.read(STREAM)).contains(5L);
    }

    @Test
    void only_the_poison_item_in_a_batch_is_dead_lettered_and_its_neighbours_are_projected() {
        StreamConsumer consumer = consumer(3, writer, deadLetterStore, fixedClock());
        consumer.start();

        source.queue(payload("tx-1", WHEN), 1);
        source.queue(payload("tx-poison", POISON_WHEN), 2);
        source.queue(payload("tx-3", WHEN), 3);
        source.deliverAll();

        assertThat(count("wqWebItem")).isEqualTo(2);
        assertThat(count()).isEqualTo(1);
        var row = dsl().fetchOne(
                "select \"sourceId\", \"streamOffset\", \"stream\", \"source\", \"attempts\", "
                        + "\"payload\"->>'occurredAt' as at, \"payload\"->>'amount' as amount "
                        + "from \"wqDeadLetter\"");
        assertThat(row.get("sourceId", String.class)).isEqualTo("tx-poison");
        // The item's OWN offset, not the batch's highest.
        assertThat(row.get("streamOffset", Long.class)).isEqualTo(2L);
        assertThat(row.get("stream", String.class)).isEqualTo(STREAM);
        assertThat(row.get("source", Integer.class)).isEqualTo(SourceKind.TRANSACTION.code());
        // The TRUE attempt count: 2 as part of the batch + 2 alone.
        assertThat(row.get("attempts", Integer.class)).isEqualTo(4);
        // Instant serialises as ISO-8601 text (WRITE_DATES_AS_TIMESTAMPS is off in Spring Boot).
        assertThat(Instant.parse(row.get("at", String.class))).isEqualTo(POISON_WHEN);
        assertThat(new BigDecimal(row.get("amount", String.class))).isEqualByComparingTo("5.0000");
        assertThat(offsetStore.read(STREAM)).contains(3L);
        assertThat(registry.find("wqs.ingest.events").tag("outcome", "deadlettered").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("wqs.ingest.events").tag("outcome", "projected").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void the_dead_letter_row_survives_the_rolled_back_projection_transaction() {
        ProjectionItem item = payloadMapper.toItem(payload("tx-x", WHEN), txTypes);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            deadLetterStore.record(STREAM, 9L, item, new IllegalStateException("boom"), 3);
            // The enclosing (projection) transaction fails after the dead letter was written.
            status.setRollbackOnly();
        });

        assertThat(count()).isEqualTo(1);
        String error = dsl()
                .fetchOne("select \"error\" from \"wqDeadLetter\"").get(0, String.class);
        assertThat(error).contains("boom");
    }

    @Test
    void a_mapper_failure_is_dead_lettered_from_the_raw_payload_and_does_not_escape() {
        StreamConsumer consumer = consumer(1, writer, deadLetterStore, fixedClock());
        consumer.start();

        // A null amount cannot be mapped to a ProjectionItem.
        StreamPayload unmappable = new StreamPayload("tx-unmappable", 1, 7L, WHEN, "TT_BUY", "11", null, "b", "p", null, "EUR", null, null, java.util.Map.of());
        source.queue(unmappable, 1);
        source.queue(payload("tx-2", WHEN), 2);
        source.deliverAll(); // must not throw
        consumer.poll();

        assertThat(count()).isEqualTo(1);
        assertThat(dsl().fetchOne("select \"sourceId\" from \"wqDeadLetter\"").get(0, String.class))
                .isEqualTo("tx-unmappable");
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(2L);
    }

    @Test
    void an_unrecoverable_failure_never_lets_the_offset_pass_an_unprojected_event() {
        FlakyWriter flaky = new FlakyWriter(writer, 2, 5); // calls 2..5 fail: event 11, all attempts
        DeadLetterStore dbDown = new DeadLetterStore(null, null, null) {
            @Override
            public void record(String s, Long o, ProjectionItem i, Exception e, int a) {
                throw new IllegalStateException("database down");
            }
        };
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(1, flaky, dbDown, clock);
        consumer.start();

        source.queue(payload("tx-10", WHEN), 10);
        source.queue(payload("tx-11", WHEN), 11);
        source.queue(payload("tx-12", WHEN), 12);
        source.deliverAll(); // 10 projects; 11 fails everywhere; 12 arrives while recovering

        assertThat(offsetStore.read(STREAM)).contains(10L);
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(source.closeCount()).isEqualTo(1);

        // Backoff has not elapsed: nothing happens.
        clock.advance(Duration.ofSeconds(1));
        consumer.poll();
        assertThat(source.subscribeCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(10));
        consumer.poll();
        assertThat(source.subscribeCount()).isEqualTo(2);
        assertThat(source.subscribedFrom()).isEqualTo(11L); // stored offset + 1

        source.redeliverFromSubscription();
        consumer.poll();

        assertThat(count("wqWebItem")).isEqualTo(3);
        assertThat(offsetStore.read(STREAM)).contains(12L);
        assertThat(count()).isZero();
    }

    @Test
    void a_stale_subscription_cannot_feed_the_consumer_or_advance_the_offset() {
        FlakyWriter flaky = new FlakyWriter(writer, 1, 4); // event 1 fails all attempts
        DeadLetterStore dbDown = alwaysFailingStore();
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(1, flaky, dbDown, clock);
        consumer.start();

        source.queue(payload("tx-1", WHEN), 1);
        source.deliverAll(); // handler 0 -> recovery
        source.failOnClose(true); // the old subscription cannot be closed

        consumer.stop();
        consumer.start(); // handler 1, recovering reset
        clock.advance(Duration.ofSeconds(10));
        consumer.poll();
        assertThat(source.subscribeCount()).isEqualTo(2); // poll did not re-subscribe again

        // Stale handler 0 delivers far ahead; it must be ignored.
        source.deliverVia(0, payload("tx-1000", WHEN), 1000);
        consumer.poll();
        assertThat(count("wqWebItem")).isZero();
        assertThat(offsetStore.read(STREAM)).isEmpty();

        // The live handler (1) feeds normally.
        source.deliverVia(1, payload("tx-1", WHEN), 1);
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(1L);
    }

    @Test
    void a_failed_source_close_during_recovery_does_not_leave_the_old_subscription_live() {
        FlakyWriter flaky = new FlakyWriter(writer, 1, 4);
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(1, flaky, alwaysFailingStore(), clock);
        consumer.start();
        source.queue(payload("tx-1", WHEN), 1);
        source.deliverAll();
        source.failOnClose(true);

        clock.advance(Duration.ofSeconds(10));
        consumer.poll(); // close throws (swallowed), re-subscribes
        assertThat(source.subscribeCount()).isEqualTo(2);

        source.deliverVia(0, payload("tx-999", WHEN), 999);
        assertThat(count("wqWebItem")).isZero();
        assertThat(offsetStore.read(STREAM)).isEmpty();
    }

    @Test
    void an_error_thrown_by_the_writer_enters_recovery_and_is_rethrown() {
        ProjectionWriter erroring = new ProjectionWriter(null, null, null, null, null, null, null, null, null, null) {
            private int calls;

            @Override
            public WriteResult apply(ProjectionBatch batch) {
                if (++calls == 1) {
                    throw new StackOverflowError("simulated");
                }
                return writer.apply(batch);
            }
        };
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(1, erroring, deadLetterStore, clock);
        consumer.start();
        source.queue(payload("tx-1", WHEN), 1);
        source.queue(payload("tx-2", WHEN), 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(source::deliverAll)
                .isInstanceOf(StackOverflowError.class);
        // tx-1 was drained but not applied: nothing stored, buffer dropped.
        assertThat(offsetStore.read(STREAM)).isEmpty();
        source.deliverVia(0, payload("tx-2", WHEN), 2); // ignored while recovering
        assertThat(offsetStore.read(STREAM)).isEmpty();

        clock.advance(Duration.ofSeconds(10));
        consumer.poll();
        assertThat(source.subscribedFrom()).isZero();
        source.redeliverFromSubscription();
        assertThat(count("wqWebItem")).isEqualTo(2);
        assertThat(offsetStore.read(STREAM)).contains(2L);
    }

    @Test
    void a_failed_dead_letter_write_for_a_later_item_does_not_move_the_offset() {
        DeadLetterStore dbDown = alwaysFailingStore();
        StreamConsumer consumer = consumer(3, writer, dbDown, fixedClock());
        consumer.start();

        // Whole batch fails (poison), item 1 projects alone, poison item 2 cannot be dead-lettered.
        source.queue(payload("tx-1", WHEN), 1);
        source.queue(payload("tx-poison", POISON_WHEN), 2);
        source.queue(payload("tx-3", WHEN), 3);
        source.deliverAll();

        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(count()).isZero();
        assertThat(offsetStore.read(STREAM)).isEmpty();
    }

    @Test
    void an_undeserializable_body_is_dead_lettered_raw_and_the_idle_offset_advances() {
        StreamConsumer consumer = consumer(10, writer, deadLetterStore, fixedClock());
        consumer.start();
        byte[] body = "{not json".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        source.deliverUndeserializable(body, 8);

        assertThat(count()).isEqualTo(1);
        var row = dsl().fetchOne(
                "select \"streamOffset\", \"stream\", \"attempts\", \"error\", "
                        + "\"payload\"->>'rawBase64' as raw from \"wqDeadLetter\"");
        assertThat(row.get("streamOffset", Long.class)).isEqualTo(8L);
        assertThat(row.get("stream", String.class)).isEqualTo(STREAM);
        assertThat(row.get("attempts", Integer.class)).isEqualTo(1);
        assertThat(row.get("error", String.class)).contains("undeserializable");
        assertThat(java.util.Base64.getDecoder().decode(row.get("raw", String.class))).isEqualTo(body);
        assertThat(registry.find("wqs.ingest.events").tag("outcome", "deadlettered").counter().count())
                .isEqualTo(1.0);
        // Nothing earlier is buffered, so the recorded poison is fully accounted for.
        assertThat(offsetStore.read(STREAM)).contains(8L);
    }

    @Test
    void an_undeserializable_body_does_not_advance_the_offset_past_buffered_events() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(10, writer, deadLetterStore, clock);
        consumer.start();

        source.queue(payload("tx-1", WHEN), 1);
        source.deliverAll();                              // buffered, not applied
        source.deliverUndeserializable(new byte[] {1, 2, 3}, 2);

        assertThat(count()).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).isEmpty();   // would skip unprojected offset 1

        source.queue(payload("tx-3", WHEN), 3);
        source.deliverAll();
        clock.advance(Duration.ofSeconds(1));
        consumer.poll();                                  // window elapsed: batch covers 1..3

        assertThat(count("wqWebItem")).isEqualTo(2);
        assertThat(offsetStore.read(STREAM)).contains(3L);
    }

    @Test
    void an_undeserializable_body_from_a_stale_subscription_is_ignored() {
        StreamConsumer consumer = consumer(10, writer, deadLetterStore, fixedClock());
        consumer.start();
        consumer.stop();
        consumer.start();

        source.deliverUndeserializableVia(0, new byte[] {9}, 77);

        assertThat(count()).isZero();
        assertThat(offsetStore.read(STREAM)).isEmpty();
    }

    @Test
    void a_failed_raw_dead_letter_write_moves_no_offset_and_enters_recovery() {
        DeadLetterStore dbDown = new DeadLetterStore(null, null, null) {
            @Override
            public void record(String s, Long o, byte[] b, Exception e, int a) {
                throw new IllegalStateException("database down");
            }
        };
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(10, writer, dbDown, clock);
        consumer.start();

        source.deliverUndeserializable(new byte[] {1}, 4); // must not throw

        assertThat(offsetStore.read(STREAM)).isEmpty();
        clock.advance(Duration.ofSeconds(10));
        consumer.poll();
        assertThat(source.subscribeCount()).isEqualTo(2);
        assertThat(source.subscribedFrom()).isZero();      // redelivered from the stored offset
    }

    @Test
    void the_source_is_never_closed_while_the_consumer_monitor_is_held() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer[] holder = new StreamConsumer[1];
        java.util.List<Boolean> heldDuringClose = new java.util.ArrayList<>();
        source.onClose(() -> heldDuringClose.add(Thread.holdsLock(holder[0])));
        holder[0] = consumer(1, new FlakyWriter(writer, 1, 4), alwaysFailingStore(), clock);

        holder[0].start();                                  // closes any leftover subscription
        source.queue(payload("tx-1", WHEN), 1);
        source.deliverAll();                                // -> recovery
        clock.advance(Duration.ofSeconds(10));
        holder[0].poll();                                   // closes, then re-subscribes
        holder[0].stop();                                   // drains, then closes

        assertThat(heldDuringClose).hasSize(3).containsOnly(false);
    }

    @Test
    void stop_makes_poll_and_recovery_no_ops_so_nothing_re_subscribes_after_stop() {
        MutableClock clock = new MutableClock(WHEN.plusSeconds(5));
        StreamConsumer consumer = consumer(1, new FlakyWriter(writer, 1, 4), alwaysFailingStore(), clock);
        consumer.start();
        source.queue(payload("tx-1", WHEN), 1);
        source.deliverAll();                                // recovering
        consumer.stop();

        clock.advance(Duration.ofSeconds(60));
        consumer.poll();

        assertThat(source.subscribeCount()).isEqualTo(1);
        // A late delivery through the pre-stop handler is inert as well.
        source.deliverVia(0, payload("tx-2", WHEN), 2);
        assertThat(count("wqWebItem")).isZero();
    }

    /**
     * C1: a U+0000 in the sourceId makes the item insert fail AND the normal dead-letter row fail
     * (Postgres text cannot hold NUL). The store must fall back to a raw row in its own
     * transaction, so the offset advances and ingestion continues -- no recovery loop.
     */
    @Test
    void an_item_whose_source_id_contains_nul_is_dead_lettered_raw_and_ingestion_continues() {
        StreamConsumer consumer = consumer(1, writer, deadLetterStore, fixedClock());
        consumer.start();

        source.queue(payload("tx\u0000bad", WHEN), 5);
        source.queue(payload("tx-6", WHEN), 6);
        source.deliverAll();
        consumer.poll();

        assertThat(count()).isEqualTo(1);
        var row = dsl().fetchOne(
                "select \"sourceId\", \"streamOffset\", \"error\", \"payload\"->>'rawBase64' as raw "
                        + "from \"wqDeadLetter\"");
        assertThat(row.get("sourceId", String.class)).isNull();
        assertThat(row.get("streamOffset", Long.class)).isEqualTo(5L);
        assertThat(row.get("error", String.class)).contains("dead-letter fallback").doesNotContain("\u0000");
        String raw = new String(java.util.Base64.getDecoder().decode(row.get("raw", String.class)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).contains("\"sourceId\":\"tx\\u0000bad\"");
        // Not stuck: the next event projected and the offset passed the poison.
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(6L);
        assertThat(source.subscribeCount()).isEqualTo(1);
    }

    @Test
    void a_nul_in_an_unmappable_payload_or_its_error_text_still_yields_a_row() {
        StreamPayload unmappable = new StreamPayload("tx\u0000x", 1, 7L, WHEN, "TT_BUY", "11", null, null, null, null, "EUR", null, null, java.util.Map.of());

        deadLetterStore.record(STREAM, 3L, unmappable, new IllegalArgumentException("bad \u0000 text"), 1);

        assertThat(count()).isEqualTo(1);
        assertThat(dsl().fetchOne("select \"error\" from \"wqDeadLetter\"").get(0, String.class))
                .startsWith("java.lang.IllegalArgumentException: bad \uFFFD text");
    }

    private static DeadLetterStore alwaysFailingStore() {
        return new DeadLetterStore(null, null, null) {
            @Override
            public void record(String s, Long o, ProjectionItem i, Exception e, int a) {
                throw new IllegalStateException("database down");
            }
        };
    }

    private StreamConsumer consumer(int batchSize, ProjectionWriter w, DeadLetterStore store, Clock clock) {
        return new StreamConsumer(
                source,
                new StreamProperties(true, STREAM, batchSize, Duration.ofMillis(1), 2, Duration.ofSeconds(5), null),
                payloadMapper, txTypes, w, offsetStore, registry, clock, store);
    }

    private static Clock fixedClock() {
        return Clock.fixed(WHEN.plusSeconds(5), ZoneOffset.UTC);
    }

    private static StreamPayload payload(String id, Instant at) {
        return new StreamPayload(id, 1, 7L, at, "TT_BUY", "11", null, "b", "p", new BigDecimal("5.0000"), "EUR", null, null, java.util.Map.of());
    }

    private int count() {
        return count("wqDeadLetter");
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }

    /** Fails calls numbered failFrom..failTo (1-based), otherwise delegates to the real writer. */
    private static final class FlakyWriter extends ProjectionWriter {
        private final ProjectionWriter delegate;
        private final int failFrom;
        private final int failTo;
        private int calls;

        FlakyWriter(ProjectionWriter delegate, int failFrom, int failTo) {
            super(null, null, null, null, null, null, null, null, null, null);
            this.delegate = delegate;
            this.failFrom = failFrom;
            this.failTo = failTo;
        }

        @Override
        public WriteResult apply(ProjectionBatch batch) {
            calls++;
            if (calls >= failFrom && calls <= failTo) {
                throw new IllegalStateException("database down (call " + calls + ")");
            }
            return delegate.apply(batch);
        }
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
