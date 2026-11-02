package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PartitionProvisioner;
import wqs.db.PartitionedTable;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
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
    @Autowired private PartitionProvisioner provisioner;

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

        // Postgres text cannot hold U+0000, so this insert fails at the database -- after tx-3
        // has been written in the same transaction.
        ProjectionItem unpartitioned = new ProjectionItem(
                7L, SourceKind.TRANSACTION, "tx-bad\u0000", WHEN,
                "P11", "instants", "b-1", "p-1", TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);

        assertThatThrownBy(() -> writer.apply(
                new ProjectionBatch(STREAM, List.of(tx("tx-3"), unpartitioned), 11L)))
                .isInstanceOf(RuntimeException.class);

        // tx-3 must not survive, and the offset must still be the previous one.
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(10L);
    }

    /** I1 / R100: a month nobody provisioned -- long past, or ahead of the runway -- still projects. */
    @Test
    void events_in_never_provisioned_past_and_future_months_project_without_precreated_partitions() {
        YearMonth past = YearMonth.now(ZoneOffset.UTC).minusYears(3);
        YearMonth future = YearMonth.now(ZoneOffset.UTC).plusMonths(11);
        dropPartitions(past);
        dropPartitions(future);
        assertThat(partitionManager.runwayMonths(past)).isZero();
        assertThat(partitionManager.runwayMonths(future)).isZero();

        writer.apply(new ProjectionBatch(STREAM, List.of(
                tx("tx-past", past.atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant()),
                tx("tx-future", future.atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant())), 5L));

        assertThat(count("wqWebItem")).isEqualTo(2);
        assertThat(count("wqSession")).isEqualTo(2);
        assertThat(partitionManager.runwayMonths(past)).isPositive();
        assertThat(partitionManager.runwayMonths(future)).isPositive();
        assertThat(offsetStore.read(STREAM)).contains(5L);
    }

    @Test
    void a_batch_with_no_offset_does_not_touch_the_offset_row() {
        writer.apply(new ProjectionBatch(STREAM, List.of(tx("tx-1")), null));

        assertThat(offsetStore.read(STREAM)).isEmpty();
    }

    private void dropPartitions(YearMonth month) {
        for (PartitionedTable table : PartitionedTable.values()) {
            dsl().execute("drop table if exists \"" + table.tableName() + "_"
                    + month.format(DateTimeFormatter.ofPattern("uuuu_MM")) + "\"");
        }
        provisioner.forgetKnownMonths();
    }

    private ProjectionItem tx(String sourceId) {
        return tx(sourceId, WHEN);
    }

    private ProjectionItem tx(String sourceId, Instant at) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, at, "P11", "instants", "b-" + sourceId, "p-1",
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }
}
