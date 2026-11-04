package wqs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.IntegrationTest;
import wqs.dto.IngestResult;
import wqs.dto.IncomingTransaction;
import wqs.dto.Source;
import wqs.exception.RejectedException;
import wqs.dto.IngestResult;

/** Session and play totals are calculated as rows arrive. Each test uses its own owner and batch. */
class SessionTotalsTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000);

    @Autowired TransactionIngestService ingest;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    private final long user = IDS.incrementAndGet();
    private final String batch = "GS-" + UUID.randomUUID();

    @Test
    void a_session_with_two_plays_adds_up() {
        ingest(row("TT_BUY", "-2", "2026-10-26T11:36:17Z", "P1"));
        ingest(row("PRIZE", "5", "2026-10-26T11:36:23Z", "P1"));
        ingest(row("TT_BUY", "-2", "2026-10-26T11:38:02Z", "P2"));

        Map<String, Object> s = session();
        assertThat(s).containsEntry("txCount", 3).containsEntry("playCount", 2)
                .containsEntry("txCountExclCancelled", 3).containsEntry("playCountExclCancelled", 2);
        assertMoney(s, "totalPlays", "4");
        assertMoney(s, "totalWins", "5");
        assertMoney(s, "net", "1");
        assertMoney(s, "netExclCancelled", "1");
        assertThat(instant(s, "startedAt")).isEqualTo(Instant.parse("2026-10-26T11:36:17Z"));
        assertThat(instant(s, "endedAt")).isEqualTo(Instant.parse("2026-10-26T11:38:02Z"));

        Map<String, Object> p1 = play("P1");
        assertThat(p1).containsEntry("txCount", 2).containsEntry("sessionId", s.get("sessionId"));
        assertMoney(p1, "totalPlays", "2");
        assertMoney(p1, "totalWins", "5");
        assertMoney(p1, "net", "3");
        assertMoney(play("P2"), "net", "-2");
    }

    @Test
    void a_zero_amount_row_is_stored_but_adds_nothing() {
        ingest(row("PRIZE", "0", "2026-10-26T11:00:00Z", "P1"));
        assertThat(count("select count(*) from \"wqPrjSession\" where \"userId\" = :user")).isZero();
        assertThat(count("select count(*) from \"wqPrjTransaction\" where \"userId\" = :user and \"zeroAmount\"")).isEqualTo(1);

        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:01Z", "P1"));
        ingest(row("PRIZE", "0", "2026-10-26T10:00:00Z", "P1")); // earlier, but does not move the start
        Map<String, Object> s = session();
        assertThat(s).containsEntry("txCount", 1).containsEntry("playCount", 1);
        assertThat(instant(s, "startedAt")).isEqualTo(Instant.parse("2026-10-26T11:00:01Z"));
    }

    @Test
    void an_earlier_row_moves_the_start_and_takes_over_the_product() {
        ingest(row("TT_BUY", "-1", "2026-10-26T12:00:00Z", "P1", "LATE_GAME"));
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:00Z", "P1", "FIRST_GAME"));

        Map<String, Object> s = session();
        assertThat(instant(s, "startedAt")).isEqualTo(Instant.parse("2026-10-26T11:00:00Z"));
        assertThat(s).containsEntry("productId", "FIRST_GAME");
        assertThat(play("P1")).containsEntry("productId", "FIRST_GAME");
    }

    @Test
    void a_duplicate_is_not_counted_twice() {
        IncomingTransaction bet = row("TT_BUY", "-2", "2026-10-26T11:00:00Z", "P1");
        assertThat(ingest(bet)).isEqualTo(IngestResult.STORED);
        assertThat(ingest(bet)).isEqualTo(IngestResult.ALREADY_STORED);

        assertThat(session()).containsEntry("txCount", 1);
        assertMoney(session(), "totalPlays", "2");
    }

    @Test
    void a_neutral_or_unknown_type_counts_but_moves_no_money() {
        ingest(row("TT_BUY_UNDO", "2", "2026-10-26T11:00:00Z", "P1"));
        ingest(row("SOMETHING_NEW", "7", "2026-10-26T11:00:01Z", "P1"));

        Map<String, Object> s = session();
        assertThat(s).containsEntry("txCount", 2);
        assertMoney(s, "totalPlays", "0");
        assertMoney(s, "totalWins", "0");
    }

    @Test
    void a_row_without_a_play_counts_only_in_its_session() {
        ingest(row("TT_BUY", "-3", "2026-10-26T11:00:00Z", null));

        assertThat(session()).containsEntry("txCount", 1).containsEntry("playCount", 0);
        assertThat(count("select count(*) from \"wqPrjPlay\" where \"userId\" = :user")).isZero();
    }

    @Test
    void the_same_batch_id_under_two_owner_types_is_two_sessions() {
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:00Z", "P1"));
        ingest(withOwnerType(row("TT_BUY", "-1", "2026-10-26T11:00:01Z", "P1"), (short) 2));

        assertThat(count("select count(*) from \"wqPrjSession\" where \"userId\" = :user")).isEqualTo(2);
    }

    @Test
    void a_row_in_another_currency_is_rejected_and_leaves_nothing_behind() {
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:00Z", "P1"));
        IncomingTransaction usd = withCurrency(row("TT_BUY", "-1", "2026-10-26T11:00:01Z", "P1"), "USD");

        assertThatThrownBy(() -> ingest(usd))
                .isInstanceOf(RejectedException.class)
                .satisfies(e -> assertThat(((RejectedException) e).reason()).isEqualTo("currency-mismatch"));
        assertThat(count("select count(*) from \"wqPrjTransaction\" where \"userId\" = :user")).isEqualTo(1);
        assertThat(session()).containsEntry("txCount", 1);
    }

    @Test
    void a_batch_id_on_a_source_that_cannot_carry_one_is_rejected() {
        IncomingTransaction payment = withSource(row("PT_BUY", "-1", "2026-10-26T11:00:00Z", "P1"), Source.PAYMENT);

        assertThatThrownBy(() -> ingest(payment))
                .isInstanceOf(RejectedException.class)
                .satisfies(e -> assertThat(((RejectedException) e).reason()).isEqualTo("batch-on-unbatched-source"));
        assertThat(count("select count(*) from \"wqPrjTransaction\" where \"userId\" = :user")).isZero();
    }

    // --- helpers ---

    private IngestResult ingest(IncomingTransaction t) {
        return tx.execute(status -> ingest.ingest(t));
    }

    private IncomingTransaction row(String type, String amount, String at, String subBatch) {
        return row(type, amount, at, subBatch, "GAME");
    }

    private IncomingTransaction row(String type, String amount, String at, String subBatch, String productId) {
        return new IncomingTransaction((short) 1, user, Instant.parse(at), Source.TRANSACTION, UUID.randomUUID().toString(),
                type, new BigDecimal(amount), "EUR", null, productId, "instants", batch, subBatch, null, Map.of());
    }

    private static IncomingTransaction withOwnerType(IncomingTransaction t, short ownerType) {
        return new IncomingTransaction(ownerType, t.userId(), t.occurredAt(), t.source(), t.sourceId(), t.type(), t.amount(),
                t.currency(), t.balanceAfter(), t.productId(), t.productType(), t.batchId(), t.subBatchId(),
                t.transactionReference(), t.detail());
    }

    private static IncomingTransaction withCurrency(IncomingTransaction t, String currency) {
        return new IncomingTransaction(t.ownerType(), t.userId(), t.occurredAt(), t.source(), t.sourceId(), t.type(), t.amount(),
                currency, t.balanceAfter(), t.productId(), t.productType(), t.batchId(), t.subBatchId(),
                t.transactionReference(), t.detail());
    }

    private static IncomingTransaction withSource(IncomingTransaction t, Source source) {
        return new IncomingTransaction(t.ownerType(), t.userId(), t.occurredAt(), source, t.sourceId(), t.type(), t.amount(),
                t.currency(), t.balanceAfter(), t.productId(), t.productType(), t.batchId(), t.subBatchId(),
                t.transactionReference(), t.detail());
    }

    private Map<String, Object> session() {
        return jdbc.sql("select * from \"wqPrjSession\" where \"ownerType\" = 1 and \"userId\" = :user and \"batchId\" = :batch")
                .param("user", user).param("batch", batch).query().singleRow();
    }

    private Map<String, Object> play(String subBatch) {
        return jdbc.sql("select * from \"wqPrjPlay\" where \"ownerType\" = 1 and \"userId\" = :user and \"batchId\" = :batch and \"subBatchId\" = :sub")
                .param("user", user).param("batch", batch).param("sub", subBatch).query().singleRow();
    }

    private long count(String sql) {
        return jdbc.sql(sql).param("user", user).query(Long.class).single();
    }

    /** JDBC returns timestamptz as a java.sql.Timestamp in the JVM's zone; compare instants. */
    private static Instant instant(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }

    private static void assertMoney(Map<String, Object> row, String column, String expected) {
        assertThat((BigDecimal) row.get(column)).as(column).isEqualByComparingTo(expected);
    }
}
