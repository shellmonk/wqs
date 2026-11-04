package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

/**
 * Plan D3: a cancel whose target never arrives is resolved after the threshold -- removed from
 * "wqPrjPendingCancel" and logged with ids, source and age only -- while its own transaction row stays.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class PendingCancelResolutionTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2035-03-12T13:00:00Z");
    /** Distinctive, so the log can be checked for it: the owner is PII and is never logged. */
    private static final long USER = 987_654_321L;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private PendingCancelResolution resolution;
    @Autowired private PendingCancelStore pendingCancelStore;
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2035, 3), 0);
        clearProjection();
    }

    @Test
    void a_cancel_parked_longer_than_the_threshold_is_removed_and_its_row_stays(CapturedOutput output) {
        writer.apply(new ProjectionBatch("s", List.of(cancel("c-old", "t-never"), cancel("c-young", "t-later")), 1L));
        parkedDaysAgo("c-old", 8);
        parkedDaysAgo("c-young", 6);

        int resolved = resolution.resolveOlderThan(Duration.ofDays(7));

        assertThat(resolved).isEqualTo(1);
        assertThat(db().column("select \"cancelSourceId\" from \"wqPrjPendingCancel\"", String.class))
                .containsExactly("c-young");
        assertThat(db().column("select \"sourceId\" from \"wqPrjTransaction\" order by 1", String.class))
                .as("the cancels stay ordinary rows").containsExactly("c-old", "c-young");
        assertThat(output.getAll())
                .contains("c-old").contains("TRANSACTION").contains("t-never").contains("PT168H").contains("parked PT19")
                .doesNotContain("c-young")
                .doesNotContain(Long.toString(USER));
        assertThat(new ProjectionInvariants(jdbc()).drift()).as("a resolved cancel is no drift").isEmpty();
    }

    /**
     * Final-review M8: a run logs its first removals one WARN line each and the rest in one summary
     * line with their count -- ids, source and age only, never the owner.
     */
    @Test
    void a_large_run_logs_the_first_removals_and_then_one_summary(CapturedOutput output) {
        int n = PendingCancelResolution.LOGGED_INDIVIDUALLY + 7;
        List<ProjectionTransaction> cancels = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            cancels.add(cancel("c-many-" + i, "t-many-" + i));
        }
        writer.apply(new ProjectionBatch("s", cancels, 1L));
        db().execute("update \"wqPrjPendingCancel\" set \"parkedAt\" = now() - make_interval(days => 8)");

        assertThat(resolution.resolveOlderThan(Duration.ofDays(7))).isEqualTo(n);

        String log = output.getAll();
        long individual = log.lines().filter(l -> l.contains("Resolved parked cancel c-many-")).count();
        assertThat(individual).isEqualTo(PendingCancelResolution.LOGGED_INDIVIDUALLY);
        assertThat(log).contains("Resolved 7 more parked cancels unapplied").contains(n + " in all")
                .doesNotContain(Long.toString(USER));
        assertThat(pendingCancelStore.depth()).isZero();
    }

    @Test
    void a_target_arriving_after_resolution_is_not_cancelled() {
        writer.apply(new ProjectionBatch("s", List.of(cancel("c-old", "t-late")), 1L));
        parkedDaysAgo("c-old", 8);
        resolution.resolveOlderThan(Duration.ofDays(7));

        writer.apply(new ProjectionBatch("s", List.of(original("t-late")), 2L));

        assertThat(db().value("select \"cancelled\" from \"wqPrjTransaction\" where \"sourceId\" = 't-late'",
                Boolean.class)).isFalse();
        assertThat(pendingCancelStore.depth()).isZero();
        assertThat(new ProjectionInvariants(jdbc()).drift())
                .as("its target arrived after the resolution: legitimately uncancelled").isEmpty();
    }

    /** Control: a cancel that vanished from the parked cancels before the threshold is still drift. */
    @Test
    void a_young_cancel_removed_without_resolution_is_drift() {
        writer.apply(new ProjectionBatch("s", List.of(cancel("c-lost", "t-lost")), 1L));
        parkedDaysAgo("c-lost", 6);
        db().execute("delete from \"wqPrjPendingCancel\"");

        assertThat(new ProjectionInvariants(jdbc()).drift()).singleElement().asString()
                .startsWith("missing pending cancel").contains("cancelSourceId=c-lost");
        writer.apply(new ProjectionBatch("s", List.of(original("t-lost")), 2L));
        assertThat(new ProjectionInvariants(jdbc()).drift()).singleElement().asString()
                .startsWith("cancel not applied").contains("sourceId=t-lost");
    }

    @Test
    void nothing_younger_than_the_threshold_is_removed() {
        writer.apply(new ProjectionBatch("s", List.of(cancel("c-1", "t-1")), 1L));

        assertThat(resolution.resolveOlderThan(Duration.ofDays(7))).isZero();
        assertThat(pendingCancelStore.depth()).isEqualTo(1);
    }

    @Test
    void the_threshold_must_be_positive() {
        assertThatThrownBy(() -> resolution.resolveOlderThan(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The cancel arrived {@code days} ago: it is parked in its own insert's transaction, so its
     * "parkedAt" and its row's "ingestedAt" age together.
     */
    private void parkedDaysAgo(String cancelSourceId, int days) {
        db().execute("update \"wqPrjPendingCancel\" set \"parkedAt\" = now() - make_interval(days => ?)"
                + " where \"cancelSourceId\" = ?", days, cancelSourceId);
        db().execute("update \"wqPrjTransaction\" set \"ingestedAt\" = now() - make_interval(days => ?)"
                + " where \"sourceId\" = ?", days, cancelSourceId);
    }

    private static ProjectionTransaction cancel(String id, String target) {
        return new ProjectionTransaction(
                USER, SourceKind.TRANSACTION, id, T0, Products.id(3), Products.type(3), null, null,
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, target, null);
    }

    private static ProjectionTransaction original(String id) {
        return new ProjectionTransaction(
                USER, SourceKind.TRANSACTION, id, T0.minusSeconds(60), Products.id(3), Products.type(3), null, null,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }
}
