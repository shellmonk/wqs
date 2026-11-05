package wqs.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import wqs.util.Hashes;

/**
 * Messages published to the real stream end up in Postgres. Tests share one stream and one
 * consumer, so each uses its own ids and waits on its own rows.
 */
class StreamIngestTest extends StreamTestBase {

    @Autowired StreamConsumer streamConsumer;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void theCapturedMessagesAreStored() throws Exception {
        String id1 = nextId(), id2 = nextId();
        publish(message("transfer-out.json", id1, "1-" + id1, "2026-10-29T11:22:04.465648Z"));
        publish(message("instants-prize.json", id2, "1-" + id2, "2026-10-26T11:36:23.049062Z"));

        awaitRow(id2);
        Map<String, Object> row = jdbcClient.sql("""
                select ownerId, created, source, transactionType, amount, currency, totalPostBalance,
                       productId, productType, batchId, subBatchId, transactionReference,
                       userType, userId, accountId, extSystemId, batchEnded, issued
                from wqPrjTransactions where sourceId = :id
                """).param("id", id2).query().singleRow();
        // JDBC returns smallint as Integer.
        assertThat(row).containsEntry("ownerId", "1-" + id2)
                .containsEntry("source", 1)
                .containsEntry("transactionType", "PRIZE")
                .containsEntry("currency", "EUR")
                .containsEntry("totalPostBalance", new BigDecimal("1156.000"))
                .containsEntry("productId", "TIKIKALACABANA")
                .containsEntry("productType", "instants")
                .containsEntry("batchId", "GS-ur5z5sgfr5de5flhjd6ycryf6u")
                .containsEntry("subBatchId", "PS-62pflumf4bcz7jdtfc3wl5yqfm");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("0"); // zero amounts are stored
        assertThat(row).containsEntry("userType", 1)
                .containsEntry("userId", id2) // the halves of ownerId, derived by the table
                .containsEntry("accountId", "AT_MAIN-0")
                .containsEntry("extSystemId", "PDS")
                .containsEntry("batchEnded", true);
        assertThat(row.get("issued")).isNotNull();
        assertThat(rows(id1)).isEqualTo(1);
    }

    @Test
    void aRedeliveredMessageIsStoredOnce() throws Exception {
        String id = nextId();
        byte[] body = message("transfer-out.json", id, "1-" + id, "2026-09-01T00:00:00Z");
        publish(body);
        publish(body);
        String marker = nextId();
        publish(message("transfer-out.json", marker, "1-" + marker, "2026-09-01T00:00:00Z"));

        awaitRow(marker); // the stream is ordered: both copies were handled before the marker
        assertThat(rows(id)).isEqualTo(1);
        // The second copy is a quiet duplicate, not a failed write.
        assertThat(jdbcClient.sql("select count(*) from wqDeadLetters where sourceId = :id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void theSameNumberUnderTwoOwnerTypesIsTwoOwners() throws Exception {
        String player = nextId(), retailer = nextId();
        publish(message("transfer-out.json", player, "1-777", "2026-09-02T00:00:00Z"));
        publish(message("transfer-out.json", retailer, "2-777", "2026-09-02T00:00:00Z"));

        awaitRow(retailer);
        assertThat(jdbcClient.sql("select ownerId from wqPrjTransactions where sourceId in (:a, :b) order by ownerId")
                .param("a", player).param("b", retailer).query(String.class).list())
                .containsExactly("1-777", "2-777");
    }

    @Test
    void anUnreadableMessageIsDeadLetteredByHashAndTheStreamMovesOn() throws Exception {
        byte[] poison = ("{\"id\": \"" + nextId() + "\", \"toDescription\": \"WITHDRAWAL*5208\"").getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.sha256(poison);
        publish(poison);
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-03T00:00:00Z"));

        awaitRow(after);
        Map<String, Object> dead = jdbcClient.sql("""
                select reason, error, payload, streamOffset from wqDeadLetters where bodySha256 = :sha
                """).param("sha", sha).query().singleRow();
        assertThat(dead).containsEntry("reason", "unreadable").containsEntry("error", "not valid JSON");
        assertThat(dead.get("payload")).isNull(); // the raw message, with its card digits, is never stored
        assertThat(dead.get("streamOffset")).isNotNull();
    }

    @Test
    void aNulCharacterCannotStallTheStream() throws Exception {
        String id = nextId();
        ObjectNode node = (ObjectNode) objectMapper.readTree(message("transfer-out.json", id, "1-" + id, "2026-09-06T00:00:00Z"));
        node.put("description", "A\u0000B");
        byte[] body = objectMapper.writeValueAsBytes(node);
        publish(body);
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-06T00:00:00Z"));

        awaitRow(after);
        assertThat(jdbcClient.sql("select reason from wqDeadLetters where bodySha256 = :sha")
                .param("sha", Hashes.sha256(body)).query(String.class).single()).isEqualTo("unreadable");
    }

    @Test
    void aGarbageTimestampIsDeadLetteredAndCreatesNoPartition() throws Exception {
        String id = nextId();
        publish(message("transfer-out.json", id, "1-" + id, "2199-01-01T00:00:00Z"));
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-04T00:00:00Z"));

        awaitRow(after);
        Map<String, Object> dead = jdbcClient.sql("select reason, payload::text as payload from wqDeadLetters where sourceId = :id")
                .param("id", id).query().singleRow();
        assertThat(dead.get("reason")).isEqualTo("occurred-at-out-of-range");
        assertThat(dead.get("payload").toString()).contains("\"id\": \"" + id + "\"").doesNotContain("5208").doesNotContain("toDescription");
        assertThat(rows(id)).isZero();
        assertThat(jdbcClient.sql("select to_regclass('wqPrjTransactions_2199_01') is null").query(Boolean.class).single()).isTrue();
    }

    @Test
    void aRejectedRowIsDeadLetteredWithItsReasonAndTheStreamMovesOn() throws Exception {
        String first = nextId(), usd = nextId(), after = nextId();
        publish(message("instants-prize.json", first, "1-" + first, "2026-09-07T00:00:00Z", "PRIZE", 5, "EUR"));
        publish(message("instants-prize.json", usd, "1-" + first, "2026-09-07T00:00:01Z", "PRIZE", 5, "USD"));
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-07T00:00:02Z"));

        awaitRow(after);
        assertThat(rows(usd)).isZero();
        assertThat(jdbcClient.sql("select reason from wqDeadLetters where sourceId = :id")
                .param("id", usd).query(String.class).single()).isEqualTo("currency-mismatch");
        assertThat(jdbcClient.sql("select txCount from wqPrjSessions where ownerId = :owner")
                .param("owner", "1-" + first).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void messagesPublishedTogetherAreCommittedInBatches() throws Exception {
        long batchesBefore = batches();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String id = nextId();
            ids.add(id);
            send(message("transfer-out.json", id, "1-" + id, "2026-09-08T00:00:00Z"));
        }
        flush();
        awaitRow(ids.getLast());

        assertThat(ids).allMatch(id -> rows(id) == 1);
        assertThat(batches() - batchesBefore).as("transactions for 200 messages").isLessThan(50);
    }

    @Test
    void aRejectedMessageInsideABatchIsDeadLetteredOnceAndTheRestStoredOnce() throws Exception {
        String owner = nextId();
        send(message("instants-prize.json", nextId(), "1-" + owner, "2026-09-09T00:00:00Z", "PRIZE", 5, "EUR"));
        List<String> ids = new java.util.ArrayList<>();
        String usd = null;
        for (int i = 0; i < 40; i++) {
            String id = nextId();
            if (i == 20) {
                usd = id;
                send(message("instants-prize.json", id, "1-" + owner, "2026-09-09T00:00:01Z", "PRIZE", 5, "USD"));
            } else {
                ids.add(id);
                send(message("transfer-out.json", id, "1-" + id, "2026-09-09T00:00:02Z"));
            }
        }
        flush();
        awaitRow(ids.getLast());

        assertThat(ids).allMatch(id -> rows(id) == 1);
        assertThat(rows(usd)).isZero();
        assertThat(jdbcClient.sql("select count(*) from wqDeadLetters where sourceId = :id")
                .param("id", usd).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aBatchSpanningMonthsWithoutPartitionsIsStoredAndReadsKeepWorking() throws Exception {
        // 2023 has no partitions yet: one batch reaching a new month must not wait for its own lock.
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = nextId();
            ids.add(id);
            String month = i < 10 ? "2023-01-31T23:59:" : i < 20 ? "2023-02-01T00:00:" : "2023-03-01T00:00:";
            send(message("transfer-out.json", id, "1-" + id, month + String.format("%02d", i % 60) + "Z"));
        }
        flush();
        await().atMost(Duration.ofSeconds(20)).until(() -> ids.stream().allMatch(id -> rows(id) == 1));
        assertThat(jdbcClient.sql("select count(*) from wqPrjTransactions where created < '2023-04-01'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void eachMonthGetsItsOwnPartition() throws Exception {
        String id = nextId();
        publish(message("transfer-out.json", id, "1-" + id, "2025-03-31T23:59:59.999999Z"));

        awaitRow(id);
        String partition = jdbcClient.sql("""
                select tableoid::regclass::text from wqPrjTransactions where sourceId = :id
                """).param("id", id).query(String.class).single();
        assertThat(partition).isEqualTo("wqprjtransactions_2025_03");
    }

    @Test
    void afterARestartConsumptionResumesAfterTheStoredOffset() throws Exception {
        byte[] poison = ("not json " + nextId()).getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.sha256(poison);
        publish(poison);
        String before = nextId();
        publish(message("transfer-out.json", before, "1-" + before, "2026-09-05T00:00:00Z"));
        awaitRow(before);

        streamConsumer.stop();
        streamConsumer.start();
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-05T00:00:00Z"));
        awaitRow(after);

        // Re-reading from the start would dead-letter the poison message a second time.
        assertThat(jdbcClient.sql("select count(*) from wqDeadLetters where bodySha256 = :sha")
                .param("sha", sha).query(Long.class).single()).isEqualTo(1);
        long stored = jdbcClient.sql("select streamOffset from wqIngestCursors where feed = :feed")
                .param("feed", STREAM).query(Long.class).single();
        assertThat(stored).isNotNegative();
    }

    private long batches() {
        var summary = meterRegistry.find("wqs.ingest.stream.batch.size").summary();
        return summary == null ? 0 : summary.count();
    }
}
