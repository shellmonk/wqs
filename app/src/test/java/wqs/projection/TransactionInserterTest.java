package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

class TransactionInserterTest extends PostgresTestBase {

    private static final Instant WHEN = Instant.parse("2033-04-10T08:30:00Z");

    private TransactionInserter inserter;

    @BeforeEach
    void setUp() {
        new PartitionManager(jdbc()).ensurePartitionsAhead(java.time.YearMonth.of(2033, 4), 0);
        inserter = new TransactionInserter(jdbc());
        db().execute("delete from \"wqPrjTransaction\"");
    }

    @Test
    void a_transaction_is_stored_once_and_seen_through_both_views() {
        assertThat(inserter.insertIfAbsent(transaction("tx-1"))).isTrue();

        assertThat(count("wqPrjTransaction")).isEqualTo(1);
        assertThat(count(wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS)).isEqualTo(1);
        assertThat(count("wqPrjTransaction")).isEqualTo(1);
    }

    @Test
    void a_payment_is_seen_only_through_the_operator_view() {
        ProjectionTransaction payment = new ProjectionTransaction(
                7L, SourceKind.PAYMENT, "pay-1", WHEN, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("100.0000"), "EUR", null, null, null);

        assertThat(inserter.insertIfAbsent(payment)).isTrue();

        assertThat(count(wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS)).isZero();
        assertThat(count("wqPrjTransaction")).isEqualTo(1);
    }

    @Test
    void replaying_the_same_event_is_a_no_op() {
        inserter.insertIfAbsent(transaction("tx-1"));

        boolean secondInsert = inserter.insertIfAbsent(transaction("tx-1"));

        assertThat(secondInsert).isFalse();
        assertThat(count("wqPrjTransaction")).isEqualTo(1);
        assertThat(count(wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS)).isEqualTo(1);
        assertThat(count("wqPrjTransaction")).isEqualTo(1);
    }

    /** Plan D2: the duplicate key is (source, sourceId, occurredAt); sourceId alone is not unique across sources. */
    @Test
    void the_same_sourceId_of_another_source_is_not_a_duplicate() {
        assertThat(inserter.insertIfAbsent(transaction("id-1"))).isTrue();
        ProjectionTransaction payment = new ProjectionTransaction(
                7L, SourceKind.PAYMENT, "id-1", WHEN, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("100.0000"), "EUR", null, null, null);

        assertThat(inserter.insertIfAbsent(payment)).isTrue();
        assertThat(inserter.insertIfAbsent(payment)).isFalse();
        assertThat(count("wqPrjTransaction")).isEqualTo(2);
    }

    /** A replay conflicts on the unique key, so it never draws a second row id either. */
    @Test
    void a_replay_conflicts_on_the_unique_key_and_stores_nothing() {
        inserter.insertIfAbsent(transaction("tx-1"));
        long id = db().value("select \"transactionId\" from \"wqPrjTransaction\"", Long.class);

        assertThat(inserter.insertIfAbsent(transaction("tx-1"))).isFalse();

        assertThat(db().value("select \"transactionId\" from \"wqPrjTransaction\"", Long.class)).isEqualTo(id);
        assertThat(db().value("select \"versionAt\" is null from \"wqPrjTransaction\"", Boolean.class))
                .as("a stream transaction is unversioned").isTrue();
    }

    @Test
    void detail_json_is_stored_as_jsonb() {
        ProjectionTransaction withDetail = new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, "tx-detail", WHEN, "P11", "instants", "b-1", "p-1",
                TxTypes.PLAY, new BigDecimal("2.5000"), "EUR", null, null,
                "{\"gameCode\":\"CRZ8\"}");

        inserter.insertIfAbsent(withDetail);

        String gameCode = db().value("""
                          select "detail" ->> 'gameCode' from (select * from "wqPrjTransaction" where "source" in (1, 3)) w where "sourceId" = ?
                          """, String.class, "tx-detail");
        assertThat(gameCode).isEqualTo("CRZ8");
    }

    @Test
    void every_field_is_written_to_both_views_with_one_transaction_id() {
        ProjectionTransaction transaction = new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, "tx-full", WHEN, "P11", "instants", "batch-A", "sub-B",
                TxTypes.NEUTRAL, new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"),
                "tx-cancelled", "{\"gameCode\":\"CRZ8\"}");
        assertThat(inserter.insertIfAbsent(transaction)).isTrue();

        long locatorId = db().value(
                "select \"transactionId\" from \"wqPrjTransaction\" where \"source\" = ? and \"sourceId\" = ?", Long.class,
                SourceKind.TRANSACTION.code(), "tx-full");

        for (String table : new String[] {wqs.projection.ProjectionInvariants.WEB_TRANSACTIONS, "wqPrjTransaction"}) {
            var row = db().row(
                    "select \"userId\", \"transactionId\", \"source\", \"sourceId\", \"occurredAt\", "
                            + "\"productId\", \"productType\", \"batchId\", \"subBatchId\", \"type\", \"amount\", "
                            + "\"currency\", \"balanceAfter\", \"cancels\", \"cancelled\", "
                            + "\"detail\" ->> 'gameCode' as game from " + ProjectionInvariants.relation(table)
                            + " where \"sourceId\" = ?",
                    "tx-full");
            assertThat(row.get("userId", Long.class)).as(table).isEqualTo(7L);
            assertThat(row.get("transactionId", Long.class)).as(table).isEqualTo(locatorId);
            assertThat(row.get("source", Integer.class)).as(table).isEqualTo(SourceKind.TRANSACTION.code());
            assertThat(row.get("occurredAt", Instant.class)).as(table).isEqualTo(WHEN);
            assertThat(row.get("productId", String.class)).as(table).isEqualTo("P11");
            assertThat(row.get("productType", String.class)).as(table).isEqualTo("instants");
            assertThat(row.get("batchId", String.class)).as(table).isEqualTo(transaction.batchId());
            assertThat(row.get("subBatchId", String.class)).as(table).isEqualTo(transaction.subBatchId());
            assertThat(row.get("type", String.class)).as(table).isEqualTo(transaction.type());
            assertThat(row.get("amount", BigDecimal.class)).as(table).isEqualByComparingTo(transaction.amount());
            assertThat(row.get("currency", String.class)).as(table).isEqualTo("EUR");
            assertThat(row.get("balanceAfter", BigDecimal.class)).as(table)
                    .isEqualByComparingTo(transaction.balanceAfter());
            assertThat(row.get("cancels", String.class)).as(table).isEqualTo(transaction.cancels());
            assertThat(row.get("cancelled", Boolean.class)).as(table).isFalse();
            assertThat(row.get("game", String.class)).as(table).isEqualTo("CRZ8");
        }
    }

    private ProjectionTransaction transaction(String sourceId) {
        return new ProjectionTransaction(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P11", "instants", "b-1", "p-1",
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"), null, null);
    }

    private int count(String table) {
        return db().value("select count(*)::int from " + wqs.projection.ProjectionInvariants.relation(table), Integer.class);
    }
}
