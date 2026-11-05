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
import wqs.TestTransactions;
import wqs.dto.IngestResult;
import wqs.exception.RejectedException;
import wqs.dto.IngestResult;

/** Session and play totals are calculated as rows arrive. Each test uses its own owner and batch. */
class ProjectionWriterIntegrationTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000);

    @Autowired ProjectionWriter projectionWriter;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcClient jdbcClient;

    private final long number = IDS.incrementAndGet();
    private final String owner = "1-" + number;
    private final String batch = "GS-" + UUID.randomUUID();

    @Test
    void aSessionWithTwoPlaysAddsUp() {
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
    void aZeroAmountRowIsStoredButAddsNothing() {
        ingest(row("PRIZE", "0", "2026-10-26T11:00:00Z", "P1"));
        assertThat(count("select count(*) from wqPrjSessions where ownerId = :owner")).isZero();
        assertThat(count("select count(*) from wqPrjTransactions where ownerId = :owner and zeroAmount")).isEqualTo(1);

        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:01Z", "P1"));
        ingest(row("PRIZE", "0", "2026-10-26T10:00:00Z", "P1")); // earlier, but does not move the start
        Map<String, Object> s = session();
        assertThat(s).containsEntry("txCount", 1).containsEntry("playCount", 1);
        assertThat(instant(s, "startedAt")).isEqualTo(Instant.parse("2026-10-26T11:00:01Z"));
    }

    @Test
    void anEarlierRowMovesTheStartAndTakesOverTheProduct() {
        ingest(row("TT_BUY", "-1", "2026-10-26T12:00:00Z", "P1").productId("LATE_GAME"));
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:00Z", "P1").productId("FIRST_GAME"));

        Map<String, Object> s = session();
        assertThat(instant(s, "startedAt")).isEqualTo(Instant.parse("2026-10-26T11:00:00Z"));
        assertThat(s).containsEntry("productId", "FIRST_GAME");
        assertThat(play("P1")).containsEntry("productId", "FIRST_GAME");
    }

    @Test
    void aDuplicateIsNotCountedTwice() {
        TestTransactions.Builder bet = row("TT_BUY", "-2", "2026-10-26T11:00:00Z", "P1");
        assertThat(ingest(bet)).isEqualTo(IngestResult.STORED);
        assertThat(ingest(bet)).isEqualTo(IngestResult.ALREADY_STORED);

        assertThat(session()).containsEntry("txCount", 1);
        assertMoney(session(), "totalPlays", "2");
    }

    @Test
    void aNeutralOrUnknownTypeCountsButMovesNoMoney() {
        ingest(row("TT_BUY_UNDO", "2", "2026-10-26T11:00:00Z", "P1"));
        ingest(row("SOMETHING_NEW", "7", "2026-10-26T11:00:01Z", "P1"));

        Map<String, Object> s = session();
        assertThat(s).containsEntry("txCount", 2);
        assertMoney(s, "totalPlays", "0");
        assertMoney(s, "totalWins", "0");
    }

    @Test
    void aRowWithoutAPlayCountsOnlyInItsSession() {
        ingest(row("TT_BUY", "-3", "2026-10-26T11:00:00Z", null));

        assertThat(session()).containsEntry("txCount", 1).containsEntry("playCount", 0);
        assertThat(count("select count(*) from wqPrjPlays where ownerId = :owner")).isZero();
    }

    @Test
    void theSameBatchIdUnderTwoOwnersIsTwoSessions() {
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:00Z", "P1"));
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:01Z", "P1").owner("2-" + number));

        assertThat(jdbcClient.sql("select count(*) from wqPrjSessions where ownerId in (:a, :b) and batchId = :batch")
                .param("a", owner).param("b", "2-" + number).param("batch", batch).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aRowInAnotherCurrencyIsRejectedAndLeavesNothingBehind() {
        ingest(row("TT_BUY", "-1", "2026-10-26T11:00:00Z", "P1"));
        TestTransactions.Builder usd = row("TT_BUY", "-1", "2026-10-26T11:00:01Z", "P1").currency("USD");

        assertThatThrownBy(() -> ingest(usd))
                .isInstanceOf(RejectedException.class)
                .satisfies(e -> assertThat(((RejectedException) e).reason()).isEqualTo("currency-mismatch"));
        assertThat(count("select count(*) from wqPrjTransactions where ownerId = :owner")).isEqualTo(1);
        assertThat(session()).containsEntry("txCount", 1);
    }

    // --- helpers ---

    private IngestResult ingest(TestTransactions.Builder row) {
        return transactionTemplate.execute(status -> projectionWriter.write(row.build()));
    }

    /** A row of this test's owner in this test's session; {@code subBatch} null means in no play. */
    private TestTransactions.Builder row(String transactionType, String amount, String at, String subBatch) {
        return TestTransactions.transaction(owner).transactionType(transactionType).amount(amount).at(at).session(batch, subBatch);
    }

    private Map<String, Object> session() {
        return jdbcClient.sql("select * from wqPrjSessions where ownerId = :owner and batchId = :batch")
                .param("owner", owner).param("batch", batch).query().singleRow();
    }

    private Map<String, Object> play(String subBatch) {
        return jdbcClient.sql("select * from wqPrjPlays where ownerId = :owner and batchId = :batch and subBatchId = :sub")
                .param("owner", owner).param("batch", batch).param("sub", subBatch).query().singleRow();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).param("owner", owner).query(Long.class).single();
    }

    /** JDBC returns timestamptz as a java.sql.Timestamp in the JVM's zone; compare instants. */
    private static Instant instant(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }

    private static void assertMoney(Map<String, Object> row, String column, String expected) {
        assertThat((BigDecimal) row.get(column)).as(column).isEqualByComparingTo(expected);
    }
}
