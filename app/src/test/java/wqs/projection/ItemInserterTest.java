package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

class ItemInserterTest extends PostgresTestBase {

    private static final Instant WHEN = Instant.parse("2033-04-10T08:30:00Z");

    private ItemInserter inserter;

    @BeforeEach
    void setUp() {
        new PartitionManager(jdbc()).ensurePartitionsAhead(java.time.YearMonth.of(2033, 4), 0);
        inserter = new ItemInserter(jdbc());
        dsl().execute("delete from \"wqItemLocator\"");
        dsl().execute("delete from \"wqWebItem\"");
        dsl().execute("delete from \"wqCcItem\"");
    }

    @Test
    void a_transaction_lands_in_both_view_tables() {
        assertThat(inserter.insertIfAbsent(transaction("tx-1"))).isTrue();

        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(count("wqCcItem")).isEqualTo(1);
    }

    @Test
    void a_payment_lands_only_in_the_operator_table() {
        ProjectionItem payment = new ProjectionItem(
                7L, SourceKind.PAYMENT, "pay-1", WHEN, null, null, null, null,
                TxTypes.NEUTRAL, new BigDecimal("100.0000"), "EUR", null, null, null);

        assertThat(inserter.insertIfAbsent(payment)).isTrue();

        assertThat(count("wqWebItem")).isZero();
        assertThat(count("wqCcItem")).isEqualTo(1);
    }

    @Test
    void replaying_the_same_event_is_a_no_op() {
        inserter.insertIfAbsent(transaction("tx-1"));

        boolean secondInsert = inserter.insertIfAbsent(transaction("tx-1"));

        assertThat(secondInsert).isFalse();
        assertThat(count("wqWebItem")).isEqualTo(1);
        assertThat(count("wqCcItem")).isEqualTo(1);
    }

    @Test
    void the_locator_records_where_the_item_landed() {
        inserter.insertIfAbsent(transaction("tx-1"));

        var row = dsl().fetchOne("""
                select "userId", "occurredAt" from "wqItemLocator"
                where "source" = ? and "sourceId" = ?
                """, SourceKind.TRANSACTION.code(), "tx-1");

        assertThat(row.get("userId", Long.class)).isEqualTo(7L);
        assertThat(row.get("occurredAt", Instant.class)).isEqualTo(WHEN);
    }

    @Test
    void detail_json_is_stored_as_jsonb() {
        ProjectionItem withDetail = new ProjectionItem(
                7L, SourceKind.TRANSACTION, "tx-detail", WHEN, "P11", "instants", "b-1", "p-1",
                TxTypes.PLAY, new BigDecimal("2.5000"), "EUR", null, null,
                "{\"gameCode\":\"CRZ8\"}");

        inserter.insertIfAbsent(withDetail);

        String gameCode = dsl()
                .fetchOne("""
                          select "detail" ->> 'gameCode' from "wqWebItem" where "sourceId" = ?
                          """, "tx-detail")
                .get(0, String.class);
        assertThat(gameCode).isEqualTo("CRZ8");
    }

    @Test
    void every_field_is_written_to_both_views_with_the_locator_item_id() {
        ProjectionItem item = new ProjectionItem(
                7L, SourceKind.TRANSACTION, "tx-full", WHEN, "P11", "instants", "batch-A", "sub-B",
                TxTypes.NEUTRAL, new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"),
                "tx-cancelled", "{\"gameCode\":\"CRZ8\"}");
        assertThat(inserter.insertIfAbsent(item)).isTrue();

        long locatorId = dsl().fetchOne(
                "select \"itemId\" from \"wqItemLocator\" where \"source\" = ? and \"sourceId\" = ?",
                SourceKind.TRANSACTION.code(), "tx-full").get(0, Long.class);

        for (String table : new String[] {"wqWebItem", "wqCcItem"}) {
            var row = dsl().fetchOne(
                    "select \"userId\", \"itemId\", \"source\", \"sourceId\", \"occurredAt\", "
                            + "\"productId\", \"productType\", \"batchId\", \"subBatchId\", \"type\", \"amount\", "
                            + "\"currency\", \"balanceAfter\", \"cancels\", \"cancelled\", "
                            + "\"detail\" ->> 'gameCode' as game from \"" + table + "\" where \"sourceId\" = ?",
                    "tx-full");
            assertThat(row.get("userId", Long.class)).as(table).isEqualTo(7L);
            assertThat(row.get("itemId", Long.class)).as(table).isEqualTo(locatorId);
            assertThat(row.get("source", Integer.class)).as(table).isEqualTo(SourceKind.TRANSACTION.code());
            assertThat(row.get("occurredAt", Instant.class)).as(table).isEqualTo(WHEN);
            assertThat(row.get("productId", String.class)).as(table).isEqualTo("P11");
            assertThat(row.get("productType", String.class)).as(table).isEqualTo("instants");
            assertThat(row.get("batchId", String.class)).as(table).isEqualTo(item.batchId());
            assertThat(row.get("subBatchId", String.class)).as(table).isEqualTo(item.subBatchId());
            assertThat(row.get("type", Integer.class)).as(table).isEqualTo((int) item.type());
            assertThat(row.get("amount", BigDecimal.class)).as(table).isEqualByComparingTo(item.amount());
            assertThat(row.get("currency", String.class)).as(table).isEqualTo("EUR");
            assertThat(row.get("balanceAfter", BigDecimal.class)).as(table)
                    .isEqualByComparingTo(item.balanceAfter());
            assertThat(row.get("cancels", String.class)).as(table).isEqualTo(item.cancels());
            assertThat(row.get("cancelled", Boolean.class)).as(table).isFalse();
            assertThat(row.get("game", String.class)).as(table).isEqualTo("CRZ8");
        }
    }

    private ProjectionItem transaction(String sourceId) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, "P11", "instants", "b-1", "p-1",
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"), null, null);
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }
}
