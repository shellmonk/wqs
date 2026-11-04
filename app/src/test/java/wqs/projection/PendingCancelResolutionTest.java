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

    private void parkedDaysAgo(String cancelSourceId, int days) {
        db().execute("update \"wqPrjPendingCancel\" set \"parkedAt\" = now() - make_interval(days => ?)"
                + " where \"cancelSourceId\" = ?", days, cancelSourceId);
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
