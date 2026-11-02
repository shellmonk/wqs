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

    // --- Phase 5 ingest rules (spec 4.1, plan D6-D8) ---------------------------------------------

    /** D8: recorded in the locator so it stays idempotent, written to nothing else. */
    @Test
    void zero_amount_item_writes_locator_only() {
        WriteResult first = writer.apply(new ProjectionBatch(STREAM, List.of(zero("z-1")), 1L));

        assertThat(count("wqItemLocator")).isEqualTo(1);
        assertThat(dsl().fetchOne("select \"zeroAmount\" from \"wqItemLocator\"").get(0, Boolean.class)).isTrue();
        assertNothingProjected();
        assertThat(first.projected()).isZero();
        assertThat(first.skipped()).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(1L);

        WriteResult again = writer.apply(new ProjectionBatch(STREAM, List.of(zero("z-1")), 2L));
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(again.skipped()).isZero();
        assertThat(count("wqItemLocator")).isEqualTo(1);
        assertNothingProjected();
    }

    /**
     * A cancel of a zero-amount item has its own amount, so it is projected; it resolves against
     * the locator row, marks nothing and never parks -- in either arrival order.
     */
    @Test
    void cancel_of_zero_amount_item_resolves_and_is_itself_projected() {
        for (boolean cancelFirst : new boolean[] {false, true}) {
            clearProjection();
            ProjectionItem target = zero("z-1");
            ProjectionItem cancel = cancelOf("c-1", "z-1", new BigDecimal("-2.0000"));
            List<ProjectionItem> order = cancelFirst ? List.of(cancel, target) : List.of(target, cancel);
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(0)), 1L));
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(1)), 2L));

            assertThat(count("wqWebItem")).as("cancelFirst=" + cancelFirst).isEqualTo(1);
            assertThat(dsl().fetchOne("select \"sourceId\" from \"wqWebItem\"").get(0, String.class)).isEqualTo("c-1");
            assertThat(dsl().fetchOne("select count(*)::int from \"wqWebItem\" where \"cancelled\"")
                    .get(0, Integer.class)).isZero();
            assertThat(count("wqPendingCancel")).as("cancelFirst=" + cancelFirst).isZero();
            assertThat(new ProjectionInvariants(dsl()).drift()).isEmpty();
        }
    }

    /** A zero-amount cancel is itself locator-only, and still cancels its (non-zero) target. */
    @Test
    void zero_amount_cancel_is_locator_only_and_still_cancels_its_target() {
        for (boolean cancelFirst : new boolean[] {false, true}) {
            clearProjection();
            ProjectionItem target = unsessioned("t-1", "lottery", new BigDecimal("3.0000"));
            ProjectionItem cancel = cancelOf("c-1", "t-1", BigDecimal.ZERO);
            List<ProjectionItem> order = cancelFirst ? List.of(cancel, target) : List.of(target, cancel);
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(0)), 1L));
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(1)), 2L));

            assertThat(count("wqItemLocator")).isEqualTo(2);
            assertThat(count("wqWebItem")).as("only the target has an item row").isEqualTo(1);
            assertThat(dsl().fetchOne("select \"cancelled\", \"cancelledBy\" from \"wqWebItem\"").intoArray())
                    .as("cancelFirst=" + cancelFirst).containsExactly(true, "c-1");
            assertThat(count("wqPendingCancel")).isZero();
            assertThat(new ProjectionInvariants(dsl()).drift()).isEmpty();
        }
    }

    /** D6 / O-4: a session is single-currency; the offending item alone is dead-lettered. */
    @Test
    void currency_mismatch_in_session_dead_letters() {
        writer.apply(new ProjectionBatch(STREAM, List.of(inSession("e-1", "EUR")), 1L));
        String sessionBefore = dsl().fetch("select * from \"wqSession\"").formatCSV();

        WriteResult result = writer.apply(new ProjectionBatch(STREAM,
                List.of(inSession("u-1", "USD"), tx("other")), 3L, List.of(2L, 3L)));

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(result.projected()).isEqualTo(1);
        assertThat(dsl().fetch("select * from \"wqSession\" where \"batchId\" = 'b-shared'").formatCSV())
                .isEqualTo(sessionBefore);
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqItemLocator\"").where("\"sourceId\" = 'u-1'")))
                .as("not even the locator").isZero();
        assertThat(deadLetters()).containsExactly("u-1|2|currency-mismatch");
        assertThat(offsetStore.read(STREAM)).contains(3L);
        assertThat(new ProjectionInvariants(dsl()).drift()).isEmpty();
    }

    /** D7 / P-7: an eInstant item must be sessioned; one that is not is dead-lettered. */
    @Test
    void einstant_without_batch_dead_letters() {
        WriteResult result = writer.apply(new ProjectionBatch(STREAM,
                List.of(unsessioned("ei-1", "instants", new BigDecimal("1.0000")),
                        unsessioned("lo-1", "lottery", new BigDecimal("1.0000"))),
                8L, List.of(7L, 8L)));

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(result.projected()).isEqualTo(1);
        assertThat(dsl().fetchCount(dsl().selectFrom("\"wqItemLocator\"").where("\"sourceId\" = 'ei-1'"))).isZero();
        assertThat(deadLetters()).containsExactly("ei-1|7|einstant-unsessioned");
        assertThat(offsetStore.read(STREAM)).contains(8L);
    }

    /**
     * F1: a cancel is exempt from the eInstant rule. It never rolls up or gets a page row, so an
     * unsessioned eInstant cancel cannot put anything into the unsessioned set; dead-lettering it
     * would leave its target silently live.
     */
    @Test
    void an_unsessioned_einstant_cancel_cancels_its_target() {
        ProjectionItem target = inSession("ei-t", "EUR");
        ProjectionItem cancel = new ProjectionItem(
                7L, SourceKind.TRANSACTION, "ei-c", WHEN.plusSeconds(60), "P11", "instants", null, null,
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "ei-t", null);

        WriteResult result = writer.apply(new ProjectionBatch(STREAM, List.of(target, cancel), 2L, List.of(1L, 2L)));

        assertThat(result.deadLettered()).isZero();
        assertThat(deadLetters()).isEmpty();
        assertThat(dsl().fetchOne("select \"cancelled\", \"cancelledBy\" from \"wqWebItem\" where \"sourceId\" = 'ei-t'")
                .intoArray()).containsExactly(true, "ei-c");
        assertThat(new ProjectionInvariants(dsl()).drift()).isEmpty();
    }

    private void assertNothingProjected() {
        for (String table : List.of("wqWebItem", "wqCcItem", "wqGroupLocator", "wqSession", "wqPlay",
                "wqWebHighLevel", "wqCcHighLevel", "wqCountDay", "wqCountMonth", "wqPendingCancel")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private List<String> deadLetters() {
        return dsl().fetch("select \"sourceId\" || '|' || coalesce(\"streamOffset\"::text, '-') || '|' || \"error\""
                + " from \"wqDeadLetter\" order by \"deadLetterId\"").getValues(0, String.class);
    }

    private ProjectionItem zero(String sourceId) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P11", "instants", "b-z", "p-z",
                TxTypes.WIN, new BigDecimal("0.0000"), "EUR", null, null, null);
    }

    private ProjectionItem cancelOf(String sourceId, String target, BigDecimal amount) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN.plusSeconds(60), "P13", "lottery", null, null,
                TxTypes.CANCEL, amount, "EUR", null, target, null);
    }

    private ProjectionItem unsessioned(String sourceId, String productType, BigDecimal amount) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P13", productType, null, null,
                TxTypes.PLAY, amount, "EUR", null, null, null);
    }

    private ProjectionItem inSession(String sourceId, String currency) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P11", "instants",
                "b-shared", "p-1", TxTypes.PLAY, new BigDecimal("5.0000"), currency, null, null, null);
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
