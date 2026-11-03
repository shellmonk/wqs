package wqs.ingest.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.db.TxTypeRegistry;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.DeltaBatch;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionInvariants;
import wqs.projection.ProjectionWriter;
import wqs.projection.source.DeltaRow;

/**
 * Plan D8: the stream consumer and the poller run concurrently, each single-threaded, and meet
 * only on the shared rollups when a sessioned prize joins a stream session. Both take the
 * documented lock order, so applying the two for one session at the same moment must neither
 * deadlock (Postgres would abort one with "deadlock detected") nor leave drift (P-4, P-8).
 */
@SpringBootTest
class StreamAndDeltaConcurrencyTest extends PostgresTestBase {

    private static final int ITERATIONS = 20;
    private static final long USER = 6161L;
    private static final Instant T0 = Instant.parse("2034-11-10T10:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private DeltaRowDecoder decoder;
    @Autowired private TxTypeRegistry txTypes;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 2);
        clearProjection();
    }

    @Test
    void stream_and_poller_concurrently_touching_one_session_do_not_deadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String batch = "conc-" + i;
                Instant at = T0.plusSeconds(600L * i);
                ProjectionBatch stream = new ProjectionBatch("wallet-transactions", List.of(
                        buy("s-" + i + "-a", at, batch), buy("s-" + i + "-b", at.plusSeconds(30), batch)), (long) i);
                DeltaRow prize = decoder.decode(objectMapper.readTree(DeltaRows.prize(
                        Integer.toString(800_000 + i), USER, at.plusSeconds(10), at.plusSeconds(20),
                        "UNCLAIMED", "instants", batch, "play-1")), txTypes);

                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<?>> runs = new ArrayList<>();
                runs.add(pool.submit(() -> {
                    start.await();
                    return writer.apply(stream);
                }));
                runs.add(pool.submit(() -> {
                    start.await();
                    return writer.applyDelta(new DeltaBatch(List.of(prize), prize.version()));
                }));
                for (Future<?> run : runs) {
                    run.get(30, TimeUnit.SECONDS); // a deadlock abort surfaces here as an exception
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqSession\""))).isEqualTo(ITERATIONS);
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqWebItem\""))).isEqualTo(3 * ITERATIONS);
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqPlay\""))).isEqualTo(ITERATIONS);
        ProjectionInvariants invariants = new ProjectionInvariants(dsl());
        assertThat(invariants.aggregationDrift()).as("P-4").isEmpty();
        assertThat(invariants.highLevelDrift()).as("P-8").isEmpty();
        assertThat(invariants.drift()).isEmpty();
    }

    private static ProjectionItem buy(String id, Instant at, String batch) {
        // In the prize's play: both writers lock the play's locator, then the session's (D8).
        return new ProjectionItem(USER, SourceKind.TRANSACTION, id, at, "TIKIKALACABANA", "instants", batch, "play-1",
                TxTypes.PLAY, new BigDecimal("2.0000"), "EUR", null, null, null);
    }
}
