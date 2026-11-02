package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.ingest.stream.StreamOffsetStore;

/** Spring context test: the transaction boundary is the behaviour under test. */
@SpringBootTest
class ProjectionWriterTest extends PostgresTestBase {

    private static final String STREAM = "wallet-transactions";
    private static final Instant WHEN = Instant.parse("2033-07-05T09:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private StreamOffsetStore offsetStore;
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2033, 7), 0);
        clearProjection();
        dsl().execute("delete from \"wqStreamOffset\"");
    }

    @Test
    void applies_a_batch_and_commits_the_offset_together() {
        WriteResult result = writer.apply(
                new ProjectionBatch(STREAM, List.of(tx("tx-1"), tx("tx-2")), 42L));

        assertThat(result.projected()).isEqualTo(2);
        assertThat(result.duplicates()).isZero();
        assertThat(offsetStore.read(STREAM)).contains(42L);
    }

    @Test
    void counts_duplicates_without_inserting_them() {
        writer.apply(new ProjectionBatch(STREAM, List.of(tx("tx-1")), 1L));

        WriteResult result = writer.apply(
                new ProjectionBatch(STREAM, List.of(tx("tx-1"), tx("tx-2")), 2L));

        assertThat(result.projected()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(2L);
    }

    @Test
    void a_failing_item_rolls_back_the_whole_batch_including_the_offset() {
        writer.apply(new ProjectionBatch(STREAM, List.of(tx("tx-1")), 10L));

        // No partition exists for 2029-01, so this insert fails at the database.
        ProjectionItem unpartitioned = new ProjectionItem(
                7L, SourceKind.TRANSACTION, "tx-bad", Instant.parse("2029-01-01T00:00:00Z"),
                11, "b-1", "p-1", (short) 1, new BigDecimal("1.0000"), "EUR", null, null, null);

        assertThatThrownBy(() -> writer.apply(
                new ProjectionBatch(STREAM, List.of(tx("tx-3"), unpartitioned), 11L)))
                .isInstanceOf(RuntimeException.class);

        // tx-3 must not survive, and the offset must still be the previous one.
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(10L);
    }

    @Test
    void a_batch_with_no_offset_does_not_touch_the_offset_row() {
        writer.apply(new ProjectionBatch(STREAM, List.of(tx("tx-1")), null));

        assertThat(offsetStore.read(STREAM)).isEmpty();
    }

    private ProjectionItem tx(String sourceId) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, 11, "b-1", "p-1",
                (short) 1, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }
}
