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

class ItemInserterTest extends PostgresTestBase {

    private static final Instant WHEN = Instant.parse("2033-04-10T08:30:00Z");

    private ItemInserter inserter;

    @BeforeEach
    void setUp() {
        new PartitionManager(dsl()).ensurePartitionsAhead(java.time.YearMonth.of(2033, 4), 0);
        inserter = new ItemInserter(dsl());
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
                7L, SourceKind.PAYMENT, "pay-1", WHEN, null, null, null,
                (short) 9, new BigDecimal("100.0000"), "EUR", null, null, null);

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
                7L, SourceKind.TRANSACTION, "tx-detail", WHEN, 11, "b-1", "p-1",
                (short) 1, new BigDecimal("2.5000"), "EUR", null, null,
                "{\"gameCode\":\"CRZ8\"}");

        inserter.insertIfAbsent(withDetail);

        String gameCode = dsl()
                .fetchOne("""
                          select "detail" ->> 'gameCode' from "wqWebItem" where "sourceId" = ?
                          """, "tx-detail")
                .get(0, String.class);
        assertThat(gameCode).isEqualTo("CRZ8");
    }

    private ProjectionItem transaction(String sourceId) {
        return new ProjectionItem(
                7L, SourceKind.TRANSACTION, sourceId, WHEN, 11, "b-1", "p-1",
                (short) 1, new BigDecimal("5.0000"), "EUR", new BigDecimal("95.0000"), null, null);
    }

    private int count(String table) {
        return dsl().fetchOne("select count(*)::int from \"" + table + "\"").get(0, Integer.class);
    }
}
