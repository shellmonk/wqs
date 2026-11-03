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
import wqs.domain.ProjectionTransaction;
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
        db().execute("delete from \"wqIngestCursor\"");
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
    void a_failing_transaction_rolls_back_the_whole_batch_including_the_offset() {
        writer.apply(new ProjectionBatch(STREAM, List.of(tx("tx-1")), 10L));

        // Postgres text cannot hold U+0000, so this insert fails at the database -- after tx-3
        // has been written in the same transaction.
        ProjectionTransaction unpartitioned = new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, "tx-bad\u0000", WHEN,
                "P11", "instants", "b-1", "p-1", TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);

        assertThatThrownBy(() -> writer.apply(
                new ProjectionBatch(STREAM, List.of(tx("tx-3"), unpartitioned), 11L)))
                .isInstanceOf(RuntimeException.class);

        // tx-3 must not survive, and the offset must still be the previous one.
        assertThat(count("wqPrjWebTransaction")).isEqualTo(1);
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

        assertThat(count("wqPrjWebTransaction")).isEqualTo(2);
        assertThat(count("wqPrjSession")).isEqualTo(2);
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
    void zero_amount_transaction_writes_locator_only() {
        WriteResult first = writer.apply(new ProjectionBatch(STREAM, List.of(zero("z-1")), 1L));

        assertThat(count("wqPrjTransactionLocator")).isEqualTo(1);
        assertThat(db().value("select \"zeroAmount\" from \"wqPrjTransactionLocator\"", Boolean.class)).isTrue();
        assertNothingProjected();
        assertThat(first.projected()).isZero();
        assertThat(first.skipped()).isEqualTo(1);
        assertThat(offsetStore.read(STREAM)).contains(1L);

        WriteResult again = writer.apply(new ProjectionBatch(STREAM, List.of(zero("z-1")), 2L));
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(again.skipped()).isZero();
        assertThat(count("wqPrjTransactionLocator")).isEqualTo(1);
        assertNothingProjected();
    }

    /**
     * A cancel of a zero-amount transaction has its own amount, so it is projected; it resolves against
     * the locator row, marks nothing and never parks -- in either arrival order.
     */
    @Test
    void cancel_of_zero_amount_transaction_resolves_and_is_itself_projected() {
        for (boolean cancelFirst : new boolean[] {false, true}) {
            clearProjection();
            ProjectionTransaction target = zero("z-1");
            ProjectionTransaction cancel = cancelOf("c-1", "z-1", new BigDecimal("-2.0000"));
            List<ProjectionTransaction> order = cancelFirst ? List.of(cancel, target) : List.of(target, cancel);
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(0)), 1L));
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(1)), 2L));

            assertThat(count("wqPrjWebTransaction")).as("cancelFirst=" + cancelFirst).isEqualTo(1);
            assertThat(db().value("select \"sourceId\" from \"wqPrjWebTransaction\"", String.class)).isEqualTo("c-1");
            assertThat(db().value("select count(*)::int from \"wqPrjWebTransaction\" where \"cancelled\"", Integer.class)).isZero();
            assertThat(count("wqPrjPendingCancel")).as("cancelFirst=" + cancelFirst).isZero();
            assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
        }
    }

    /** A zero-amount cancel is itself locator-only, and still cancels its (non-zero) target. */
    @Test
    void zero_amount_cancel_is_locator_only_and_still_cancels_its_target() {
        for (boolean cancelFirst : new boolean[] {false, true}) {
            clearProjection();
            ProjectionTransaction target = unsessioned("t-1", "lottery", new BigDecimal("3.0000"));
            ProjectionTransaction cancel = cancelOf("c-1", "t-1", BigDecimal.ZERO);
            List<ProjectionTransaction> order = cancelFirst ? List.of(cancel, target) : List.of(target, cancel);
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(0)), 1L));
            writer.apply(new ProjectionBatch(STREAM, List.of(order.get(1)), 2L));

            assertThat(count("wqPrjTransactionLocator")).isEqualTo(2);
            assertThat(count("wqPrjWebTransaction")).as("only the target has a transaction row").isEqualTo(1);
            assertThat(db().row("select \"cancelled\", \"cancelledBy\" from \"wqPrjWebTransaction\"").asMap().values().toArray())
                    .as("cancelFirst=" + cancelFirst).containsExactly(true, "c-1");
            assertThat(count("wqPrjPendingCancel")).isZero();
            assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
        }
    }

    /** D6 / O-4: a session is single-currency; the offending transaction alone is dead-lettered. */
    @Test
    void currency_mismatch_in_session_dead_letters() {
        writer.apply(new ProjectionBatch(STREAM, List.of(inSession("e-1", "EUR")), 1L));
        List<String> sessionBefore = db().lines("select * from \"wqPrjSession\"");

        WriteResult result = writer.apply(new ProjectionBatch(STREAM,
                List.of(inSession("u-1", "USD"), tx("other")), 3L, List.of(2L, 3L)));

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(result.projected()).isEqualTo(1);
        assertThat(db().lines("select * from \"wqPrjSession\" where \"batchId\" = 'b-shared'"))
                .isEqualTo(sessionBefore);
        assertThat(db().count("\"wqPrjTransactionLocator\" where \"sourceId\" = 'u-1'"))
                .as("not even the locator").isZero();
        assertThat(deadLetters()).containsExactly("u-1|2|currency-mismatch");
        assertThat(offsetStore.read(STREAM)).contains(3L);
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    /** D7 / P-7: an eInstant transaction must be sessioned; one that is not is dead-lettered. */
    @Test
    void einstant_without_batch_dead_letters() {
        WriteResult result = writer.apply(new ProjectionBatch(STREAM,
                List.of(unsessioned("ei-1", "instants", new BigDecimal("1.0000")),
                        unsessioned("lo-1", "lottery", new BigDecimal("1.0000"))),
                8L, List.of(7L, 8L)));

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(result.projected()).isEqualTo(1);
        assertThat(db().count("\"wqPrjTransactionLocator\" where \"sourceId\" = 'ei-1'")).isZero();
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
        ProjectionTransaction target = inSession("ei-t", "EUR");
        ProjectionTransaction cancel = new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, "ei-c", WHEN.plusSeconds(60), "P11", "instants", null, null,
                TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "ei-t", null);

        WriteResult result = writer.apply(new ProjectionBatch(STREAM, List.of(target, cancel), 2L, List.of(1L, 2L)));

        assertThat(result.deadLettered()).isZero();
        assertThat(deadLetters()).isEmpty();
        assertThat(db().row("select \"cancelled\", \"cancelledBy\" from \"wqPrjWebTransaction\" where \"sourceId\" = 'ei-t'").asMap().values().toArray()).containsExactly(true, "ei-c");
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    /**
     * Plan D1: a type code the enum does not list is projected, never rejected -- stored as-is in
     * both views and counted under its own code -- and rolls up NEUTRAL: it moves neither
     * totalPlays nor totalWins, while a known PLAY code in the same session does.
     */
    @Test
    void an_unknown_type_code_is_stored_as_is_and_rolls_up_neutral() {
        ProjectionTransaction unknown = new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, "tx-unknown", WHEN, "P11", "instants", "b-u", "p-1",
                "TT_NEVER_SEEN_BEFORE", new BigDecimal("-7.0000"), "EUR", null, null, null);
        ProjectionTransaction play = new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, "tx-play", WHEN.plusSeconds(1), "P11", "instants", "b-u", "p-1",
                TxTypes.PLAY, new BigDecimal("-5.0000"), "EUR", null, null, null);

        WriteResult result = writer.apply(new ProjectionBatch(STREAM, List.of(unknown, play), 1L));

        assertThat(result.projected()).isEqualTo(2);
        assertThat(deadLetters()).isEmpty();
        for (String table : List.of("wqPrjWebTransaction", "wqPrjCcTransaction")) {
            assertThat(db().value("select \"type\" from \"" + table + "\" where \"sourceId\" = 'tx-unknown'",
                    String.class)).as(table).isEqualTo("TT_NEVER_SEEN_BEFORE");
        }
        assertThat(db().value("""
                select count(*)::int from "wqPrjCountDay" where "type" = 'TT_NEVER_SEEN_BEFORE'""", Integer.class))
                .as("counted under its own code: (instants, code) and (ALL, code), per view").isEqualTo(4);
        var session = db().row("""
                select "txCount", "totalPlays", "totalWins" from "wqPrjSession" where "batchId" = 'b-u'""");
        assertThat(session.get("txCount", Integer.class)).isEqualTo(2);
        assertThat(session.get("totalPlays", BigDecimal.class)).as("only the PLAY code").isEqualByComparingTo("5.0000");
        assertThat(session.get("totalWins", BigDecimal.class)).isEqualByComparingTo("0");
    }

    private void assertNothingProjected() {
        for (String table : List.of("wqPrjTransaction", "wqPrjGroupLocator", "wqPrjSession", "wqPrjPlay",
                "wqPrjTimeline", "wqPrjCountDay", "wqPrjCountMonth", "wqPrjPendingCancel")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private List<String> deadLetters() {
        return db().column("select \"sourceId\" || '|' || coalesce(\"streamOffset\"::text, '-') || '|' || \"error\""
                + " from \"wqDeadLetter\" order by \"deadLetterId\"", String.class);
    }

    private ProjectionTransaction zero(String sourceId) {
        return new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P11", "instants", "b-z", "p-z",
                TxTypes.WIN, new BigDecimal("0.0000"), "EUR", null, null, null);
    }

    private ProjectionTransaction cancelOf(String sourceId, String target, BigDecimal amount) {
        return new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, sourceId, WHEN.plusSeconds(60), "P13", "lottery", null, null,
                TxTypes.CANCEL, amount, "EUR", null, target, null);
    }

    private ProjectionTransaction unsessioned(String sourceId, String productType, BigDecimal amount) {
        return new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P13", productType, null, null,
                TxTypes.PLAY, amount, "EUR", null, null, null);
    }

    private ProjectionTransaction inSession(String sourceId, String currency) {
        return new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P11", "instants",
                "b-shared", "p-1", TxTypes.PLAY, new BigDecimal("5.0000"), currency, null, null, null);
    }

    private void dropPartitions(YearMonth month) {
        for (PartitionedTable table : PartitionedTable.values()) {
            db().execute("drop table if exists \"" + table.tableName() + "_"
                    + month.format(DateTimeFormatter.ofPattern("uuuu_MM")) + "\"");
        }
        provisioner.forgetKnownMonths();
    }

    private ProjectionTransaction tx(String sourceId) {
        return tx(sourceId, WHEN);
    }

    private ProjectionTransaction tx(String sourceId, Instant at) {
        return new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, sourceId, at, "P11", "instants", "b-" + sourceId, "p-1",
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private int count(String table) {
        return db().value("select count(*)::int from \"" + table + "\"", Integer.class);
    }
}
