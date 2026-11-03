package wqs.ingest.delta;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.ingest.DeadLetterStore;
import wqs.ingest.stream.FakeStreamSource;
import wqs.ingest.stream.StreamConsumer;
import wqs.ingest.stream.StreamOffsetStore;
import wqs.ingest.stream.StreamProperties;
import wqs.projection.DeltaWatermarkStore;
import wqs.projection.ProjectionInvariants;
import wqs.projection.ProjectionWriter;
import wqs.projection.StreamPayloadMapper;
import wqs.projection.source.StreamPayload;

/**
 * Plan D8, ruling I2: the stream consumer and the poller run concurrently and meet on the shared
 * rollups when a sessioned prize joins a stream session. Crossed plays: the stream applies transactions
 * in play-1 then play-2 of session S (locks play-1, S, play-2), the poller a prize in play-2 of S
 * (locks play-2, S) -- opposite orders, so Postgres may abort one side with 40P01. The lock order is
 * deliberately not changed; correctness rests on both paths retrying, each through its REAL path:
 * the stream through {@link StreamConsumer} (batch retries, transactions alone, and for a lock conflict
 * recovery plus re-delivery from the stored offset -- final review I-C), the delta side through
 * {@link DeltaPoller#runOnce} (a failed run is retried like the next job fire). Asserted: eventual
 * convergence -- nothing dead-lettered, the P-4/P-8 oracles hold.
 */
@SpringBootTest
@DirtiesContext
class StreamAndDeltaConcurrencyTest extends PostgresTestBase {

    private static final FakeDeltaEndpoint ENDPOINT = new FakeDeltaEndpoint();
    private static final int ITERATIONS = 4; // most iterations deadlock once; each costs deadlock_timeout (1 s)
    private static final int MAX_POLL_ATTEMPTS = 10;
    private static final String STREAM = "conc-stream";
    private static final long USER = 6161L;
    private static final Instant T0 = Instant.parse("2034-11-10T10:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("wqs.delta.base-url", ENDPOINT::baseUrl);
    }

    @AfterAll
    static void stopEndpoint() {
        ENDPOINT.close();
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private DeltaPoller poller;
    @Autowired private DeltaWatermarkStore watermarks;
    @Autowired private StreamPayloadMapper payloadMapper;
    @Autowired private StreamOffsetStore offsetStore;
    @Autowired private DeadLetterStore deadLetterStore;
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 2);
        clearProjection();
    }

    @Test
    void stream_and_poller_concurrently_touching_crossed_plays_converge() throws Exception {
        AtomicInteger deltaDeadlocks = new AtomicInteger();
        AtomicInteger deltaOtherFailures = new AtomicInteger();
        FakeStreamSource source = new FakeStreamSource();
        StreamConsumer consumer = new StreamConsumer(source,
                new StreamProperties(true, STREAM, 2, Duration.ofSeconds(5), 3, Duration.ofMillis(50), null),
                payloadMapper, writer, offsetStore, new SimpleMeterRegistry(), Clock.systemUTC(),
                deadLetterStore);
        consumer.start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String session = "conc-" + i;
                Instant at = T0.plusSeconds(600L * i);
                long last = 2L * i + 1;
                Instant prizeVersion = at.plusSeconds(20);
                StreamPayload a = buy("s-" + i + "-a", at, session, "play-1");
                StreamPayload b = buy("s-" + i + "-b", at.plusSeconds(30), session, "play-2");
                ENDPOINT.serveByDefault(DeltaRows.page(DeltaRows.prize(Integer.toString(800_000 + i), USER,
                        at.plusSeconds(10), prizeVersion, "UNCLAIMED", "instants", session, "play-2")));

                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> stream = pool.submit(() -> {
                    start.await();
                    source.queue(a, last - 1);
                    source.queue(b, last);
                    source.deliverAll(); // a full batch of 2: applied now, retried, or into recovery
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (offsetStore.read(STREAM).orElse(-1L) < last && System.nanoTime() < deadline) {
                        Thread.sleep(60);  // past the recovery backoff
                        consumer.poll();   // re-subscribes from the stored offset
                        source.redeliverFromSubscription();
                    }
                    return null;
                });
                Future<?> delta = pool.submit(() -> {
                    start.await();
                    for (int attempt = 1; ; attempt++) { // each attempt is one job fire
                        try {
                            return poller.runOnce();
                        } catch (RuntimeException e) {
                            if (attempt >= MAX_POLL_ATTEMPTS) {
                                throw e;
                            }
                            (isDeadlock(e) ? deltaDeadlocks : deltaOtherFailures).incrementAndGet();
                        }
                    }
                });
                stream.get(60, TimeUnit.SECONDS);
                delta.get(60, TimeUnit.SECONDS);
                assertThat(offsetStore.read(STREAM)).contains(last);
                assertThat(watermarks.read()).contains(prizeVersion);
            }
        } finally {
            pool.shutdownNow();
            consumer.stop();
        }
        System.out.printf("StreamAndDeltaConcurrencyTest: %d iterations, delta 40P01 retries %d, delta other "
                + "failures %d, stream recoveries %d%n", ITERATIONS, deltaDeadlocks.get(), deltaOtherFailures.get(),
                source.subscribeCount() - 1);

        assertThat(db().count("\"wqDeadLetter\"")).isZero();
        assertThat(db().count("\"wqPrjSession\"")).isEqualTo(ITERATIONS);
        assertThat(db().count("\"wqPrjPlay\"")).isEqualTo(2 * ITERATIONS);
        assertThat(db().count("\"wqPrjWebTransaction\"")).isEqualTo(3 * ITERATIONS);
        ProjectionInvariants invariants = new ProjectionInvariants(jdbc());
        assertThat(invariants.aggregationDrift()).as("P-4").isEmpty();
        assertThat(invariants.highLevelDrift()).as("P-8").isEmpty();
        assertThat(invariants.drift()).isEmpty();
    }

    private static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PessimisticLockingFailureException
                    || (t instanceof java.sql.SQLException sql && "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static StreamPayload buy(String id, Instant at, String session, String play) {
        return new StreamPayload(id, 1, USER, at, "TT_BUY", "TIKIKALACABANA", "instants", session, play,
                new BigDecimal("2.0000"), "EUR", null, null, java.util.Map.of());
    }
}
