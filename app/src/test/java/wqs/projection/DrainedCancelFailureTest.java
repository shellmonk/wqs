package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.ingest.stream.StreamOffsetStore;

/**
 * I3: a drained cancel that fails is rolled back to its savepoint and dead-lettered ITSELF; the
 * original whose arrival drained it stays projected and the batch (and its offset) commits.
 */
@SpringBootTest
class DrainedCancelFailureTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2035-02-12T13:00:00Z");
    private static final String STREAM = "wallet-transactions";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    /**
     * The cancellation applier with one booby trap: the SECOND application of "c-boom" (the drain,
     * after its first arrival parked it) applies its effects and then throws, so the savepoint
     * has real partial writes to undo.
     */
    @TestConfiguration
    static class Trap {
        static final AtomicInteger boomCalls = new AtomicInteger();

        @Bean
        @Primary
        CancellationApplier trappedCancellationApplier(
                JdbcClient jdbc, CounterUpdater counterUpdater, PendingCancelStore pendingCancelStore,
                OwnerMonths ownerMonths) {
            return new CancellationApplier(jdbc, counterUpdater, pendingCancelStore, ownerMonths) {
                @Override
                public boolean apply(ProjectionTransaction cancel) {
                    boolean result = super.apply(cancel);
                    if (cancel.sourceId().equals("c-boom") && boomCalls.incrementAndGet() == 2) {
                        throw new IllegalStateException("simulated failure applying drained cancel");
                    }
                    return result;
                }
            };
        }
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private PartitionManager partitionManager;
    @Autowired private StreamOffsetStore offsetStore;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2035, 2), 0);
        clearProjection();
        db().execute("delete from \"wqDeadLetter\"");
        Trap.boomCalls.set(0);
    }

    @Test
    void a_failing_drained_cancel_is_dead_lettered_and_its_original_stays_projected() {
        writer.apply(new ProjectionBatch(STREAM, List.of(cancel("c-boom", "t1"), cancel("c-ok", "t2")), 1L));
        assertThat(count("\"wqPrjPendingCancel\"")).isEqualTo(2);

        WriteResult result = writer.apply(new ProjectionBatch(STREAM, List.of(tx("t1"), tx("t2")), 2L));

        assertThat(result.projected()).isEqualTo(2);
        assertThat(result.deadLettered()).isEqualTo(1);
        // The original survives, uncancelled: the failed cancel's partial writes were rolled back.
        assertThat(cancelled("t1")).isFalse();
        // The other drained cancel in the same batch still applied.
        assertThat(cancelled("t2")).isTrue();
        assertThat(count("\"wqPrjPendingCancel\"")).isZero();
        assertThat(db().row("select \"sourceId\", \"streamOffset\" from \"wqDeadLetter\"").asMap())
                .containsEntry("sourceId", "c-boom")
                .containsEntry("streamOffset", null);
        assertThat(count("\"wqDeadLetter\"")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(2L);
        ProjectionInvariants invariants = new ProjectionInvariants(jdbc());
        assertThat(invariants.counterDrift()).isEmpty();
        assertThat(invariants.aggregationDrift()).isEmpty();
        assertThat(invariants.highLevelDrift()).isEmpty();
        // The dead-lettered cancel is the one known exception, awaiting replay.
        assertThat(invariants.cancellationDrift()).singleElement().asString() // t1, stored once
                .startsWith("cancel not applied").contains("sourceId=t1");
    }

    private static ProjectionTransaction tx(String id) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, T0, "P11", "instants", "b1", "p-" + id,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private static ProjectionTransaction cancel(String id, String cancels) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, T0.plusSeconds(60), "P13", "arcade", null, null,
                TxTypes.CANCEL, new BigDecimal("5.0000"), "EUR", null, cancels, null);
    }

    private static boolean cancelled(String sourceId) {
        return db().value("select \"cancelled\" from \"wqPrjCcTransaction\" where \"sourceId\" = ?", Boolean.class, sourceId);
    }

    private static int count(String table) {
        return db().value("select count(*)::int from " + table, Integer.class);
    }
}
