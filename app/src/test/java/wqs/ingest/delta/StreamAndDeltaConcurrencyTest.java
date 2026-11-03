package wqs.ingest.delta;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.Callable;
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
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.DeltaWatermarkStore;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionInvariants;
import wqs.projection.ProjectionWriter;

/**
 * Plan D8, ruling I2: the stream consumer and the poller run concurrently and meet on the shared
 * rollups when a sessioned prize joins a stream session. Crossed plays: the stream applies items
 * in play-1 then play-2 of session S (locks play-1, S, play-2), the poller a prize in play-2 of S
 * (locks play-2, S) -- opposite orders, so Postgres may abort one side with 40P01. The lock order is
 * deliberately not changed; correctness rests on both paths retrying: the stream re-applies its
 * batch (as StreamConsumer does), the poller's run fails and the next fire re-polls. Asserted:
 * eventual convergence -- nothing dead-lettered, the P-4/P-8 oracles hold.
 */
@SpringBootTest
@DirtiesContext
class StreamAndDeltaConcurrencyTest extends PostgresTestBase {

    private static final FakeDeltaEndpoint ENDPOINT = new FakeDeltaEndpoint();
    private static final int ITERATIONS = 4; // most iterations deadlock once; each costs deadlock_timeout (1 s)
    private static final int MAX_ATTEMPTS = 10;
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
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 2);
        clearProjection();
    }

    @Test
    void stream_and_poller_concurrently_touching_crossed_plays_converge() throws Exception {
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger otherRetries = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String session = "conc-" + i;
                Instant at = T0.plusSeconds(600L * i);
                ProjectionBatch stream = new ProjectionBatch("wallet-transactions", List.of(
                        buy("s-" + i + "-a", at, session, "play-1"),
                        buy("s-" + i + "-b", at.plusSeconds(30), session, "play-2")), (long) i);
                Instant prizeVersion = at.plusSeconds(20);
                ENDPOINT.serveByDefault(DeltaRows.page(DeltaRows.prize(Integer.toString(800_000 + i), USER,
                        at.plusSeconds(10), prizeVersion, "UNCLAIMED", "instants", session, "play-2")));

                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<?>> runs = List.of(
                        pool.submit(retrying(start, deadlocks, otherRetries, () -> writer.apply(stream))),
                        // One poller run per attempt, like successive job fires.
                        pool.submit(retrying(start, deadlocks, otherRetries, () -> {
                            poller.runOnce();
                            return null;
                        })));
                for (Future<?> run : runs) {
                    run.get(60, TimeUnit.SECONDS);
                }
                assertThat(watermarks.read()).contains(prizeVersion);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.printf("StreamAndDeltaConcurrencyTest: %d iterations, %d deadlock (40P01) retries, %d other retries%n",
                ITERATIONS, deadlocks.get(), otherRetries.get());

        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqDeadLetter\""))).isZero();
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqSession\""))).isEqualTo(ITERATIONS);
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqPlay\""))).isEqualTo(2 * ITERATIONS);
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqWebItem\""))).isEqualTo(3 * ITERATIONS);
        ProjectionInvariants invariants = new ProjectionInvariants(dsl());
        assertThat(invariants.aggregationDrift()).as("P-4").isEmpty();
        assertThat(invariants.highLevelDrift()).as("P-8").isEmpty();
        assertThat(invariants.drift()).isEmpty();
    }

    /** Waits at the barrier, then runs {@code work} until it succeeds, as both production paths retry. */
    private static Callable<Object> retrying(
            CyclicBarrier start, AtomicInteger deadlocks, AtomicInteger otherRetries, Callable<?> work) {
        return () -> {
            start.await();
            for (int attempt = 1; ; attempt++) {
                try {
                    return work.call();
                } catch (RuntimeException e) {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw e;
                    }
                    if (isDeadlock(e)) {
                        deadlocks.incrementAndGet();
                    } else {
                        otherRetries.incrementAndGet();
                    }
                }
            }
        };
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

    private static ProjectionItem buy(String id, Instant at, String session, String play) {
        return new ProjectionItem(USER, SourceKind.TRANSACTION, id, at, "TIKIKALACABANA", "instants", session, play,
                TxTypes.PLAY, new BigDecimal("2.0000"), "EUR", null, null, null);
    }
}
