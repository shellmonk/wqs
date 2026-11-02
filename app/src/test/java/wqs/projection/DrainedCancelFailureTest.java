package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
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
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
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
                DSLContext dsl, CounterUpdater counterUpdater, PendingCancelStore pendingCancelStore) {
            return new CancellationApplier(dsl, counterUpdater, pendingCancelStore) {
                @Override
                public boolean apply(ProjectionItem cancel) {
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
        dsl().execute("delete from \"wqDeadLetter\"");
        Trap.boomCalls.set(0);
    }

    @Test
    void a_failing_drained_cancel_is_dead_lettered_and_its_original_stays_projected() {
        writer.apply(new ProjectionBatch(STREAM, List.of(cancel("c-boom", "t1"), cancel("c-ok", "t2")), 1L));
        assertThat(count("\"wqPendingCancel\"")).isEqualTo(2);

        WriteResult result = writer.apply(new ProjectionBatch(STREAM, List.of(tx("t1"), tx("t2")), 2L));

        assertThat(result.projected()).isEqualTo(2);
        assertThat(result.deadLettered()).isEqualTo(1);
        // The original survives, uncancelled: the failed cancel's partial writes were rolled back.
        assertThat(cancelled("t1")).isFalse();
        // The other drained cancel in the same batch still applied.
        assertThat(cancelled("t2")).isTrue();
        assertThat(count("\"wqPendingCancel\"")).isZero();
        assertThat(dsl().fetchOne("select \"sourceId\", \"streamOffset\" from \"wqDeadLetter\"").intoMap())
                .containsEntry("sourceId", "c-boom")
                .containsEntry("streamOffset", null);
        assertThat(count("\"wqDeadLetter\"")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(2L);
        assertThat(new ProjectionInvariants(dsl()).drift()).isEmpty();
    }

    private static ProjectionItem tx(String id) {
        return new ProjectionItem(1L, SourceKind.TRANSACTION, id, T0, 11, "b1", "p-" + id,
                (short) 1, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private static ProjectionItem cancel(String id, String cancels) {
        return new ProjectionItem(1L, SourceKind.TRANSACTION, id, T0.plusSeconds(60), 11, null, null,
                (short) 1, new BigDecimal("5.0000"), "EUR", null, cancels, null);
    }

    private static boolean cancelled(String sourceId) {
        return dsl().fetchOne("select \"cancelled\" from \"wqCcItem\" where \"sourceId\" = ?", sourceId)
                .get(0, Boolean.class);
    }

    private static int count(String table) {
        return dsl().fetchOne("select count(*)::int from " + table).get(0, Integer.class);
    }
}
