package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.IntegrationTest;
import wqs.ingest.DeadLetters;

/**
 * Messages published to the real stream end up in Postgres. Tests share one stream and one
 * consumer, so each uses its own ids and waits on its own rows.
 */
class StreamIngestTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000);

    @Autowired Environment streams;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired StreamConsumer consumer;

    private Producer producer;

    @BeforeEach
    void producer() {
        producer = streams.producerBuilder().stream(STREAM).build();
    }

    @AfterEach
    void close() {
        producer.close();
    }

    @Test
    void the_captured_messages_are_stored() throws Exception {
        String id1 = nextId(), id2 = nextId();
        publish(message("transfer-out.json", id1, "1-" + id1, "2026-10-29T11:22:04.465648Z"));
        publish(message("instants-prize.json", id2, "1-" + id2, "2026-10-26T11:36:23.049062Z"));

        awaitRow(id2);
        Map<String, Object> row = jdbc.sql("""
                select "ownerType", "userId", "occurredAt", "source", "type", "amount", "currency", "balanceAfter",
                       "productId", "productType", "batchId", "subBatchId", "transactionReference", "detail"::text as "detail"
                from "wqPrjTransaction" where "sourceId" = :id
                """).param("id", id2).query().singleRow();
        // JDBC returns smallint as Integer.
        assertThat(row).containsEntry("ownerType", 1)
                .containsEntry("userId", Long.parseLong(id2))
                .containsEntry("source", 1)
                .containsEntry("type", "PRIZE")
                .containsEntry("currency", "EUR")
                .containsEntry("balanceAfter", new BigDecimal("1156.0000"))
                .containsEntry("productId", "TIKIKALACABANA")
                .containsEntry("productType", "instants")
                .containsEntry("batchId", "GS-ur5z5sgfr5de5flhjd6ycryf6u")
                .containsEntry("subBatchId", "PS-62pflumf4bcz7jdtfc3wl5yqfm");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("0"); // zero amounts are stored
        assertThat(row.get("detail").toString()).contains("\"references.extSystemId\": \"PDS\"");
        assertThat(rows(id1)).isEqualTo(1);
    }

    @Test
    void a_redelivered_message_is_stored_once() throws Exception {
        String id = nextId();
        byte[] body = message("transfer-out.json", id, "1-" + id, "2026-09-01T00:00:00Z");
        publish(body);
        publish(body);
        String marker = nextId();
        publish(message("transfer-out.json", marker, "1-" + marker, "2026-09-01T00:00:00Z"));

        awaitRow(marker); // the stream is ordered: both copies were handled before the marker
        assertThat(rows(id)).isEqualTo(1);
        // The second copy is a quiet duplicate, not a failed write.
        assertThat(jdbc.sql("select count(*) from \"wqDeadLetter\" where \"sourceId\" = :id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void the_same_user_id_under_two_owner_types_is_two_owners() throws Exception {
        String player = nextId(), retailer = nextId();
        publish(message("transfer-out.json", player, "1-777", "2026-09-02T00:00:00Z"));
        publish(message("transfer-out.json", retailer, "2-777", "2026-09-02T00:00:00Z"));

        awaitRow(retailer);
        assertThat(jdbc.sql("select \"ownerType\" from \"wqPrjTransaction\" where \"sourceId\" in (:a, :b) order by \"ownerType\"")
                .param("a", player).param("b", retailer).query(Integer.class).list())
                .containsExactly(1, 2);
    }

    @Test
    void an_unreadable_message_is_dead_lettered_by_hash_and_the_stream_moves_on() throws Exception {
        byte[] poison = ("{\"id\": \"" + nextId() + "\", \"toDescription\": \"WITHDRAWAL*5208\"").getBytes(StandardCharsets.UTF_8);
        String sha = DeadLetters.sha256(poison);
        publish(poison);
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-03T00:00:00Z"));

        awaitRow(after);
        Map<String, Object> dead = jdbc.sql("""
                select "reason", "error", "payload", "streamOffset" from "wqDeadLetter" where "bodySha256" = :sha
                """).param("sha", sha).query().singleRow();
        assertThat(dead).containsEntry("reason", "unreadable").containsEntry("error", "not valid JSON");
        assertThat(dead.get("payload")).isNull(); // the raw message, with its card digits, is never stored
        assertThat(dead.get("streamOffset")).isNotNull();
    }

    @Test
    void a_nul_character_cannot_stall_the_stream() throws Exception {
        String id = nextId();
        ObjectNode node = (ObjectNode) mapper.readTree(message("transfer-out.json", id, "1-" + id, "2026-09-06T00:00:00Z"));
        node.put("description", "A\u0000B");
        byte[] body = mapper.writeValueAsBytes(node);
        publish(body);
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-06T00:00:00Z"));

        awaitRow(after);
        assertThat(jdbc.sql("select \"reason\" from \"wqDeadLetter\" where \"bodySha256\" = :sha")
                .param("sha", DeadLetters.sha256(body)).query(String.class).single()).isEqualTo("unreadable");
    }

    @Test
    void a_garbage_timestamp_is_dead_lettered_and_creates_no_partition() throws Exception {
        String id = nextId();
        publish(message("transfer-out.json", id, "1-" + id, "2199-01-01T00:00:00Z"));
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-04T00:00:00Z"));

        awaitRow(after);
        Map<String, Object> dead = jdbc.sql("select \"reason\", \"payload\"::text as \"payload\" from \"wqDeadLetter\" where \"sourceId\" = :id")
                .param("id", id).query().singleRow();
        assertThat(dead.get("reason")).isEqualTo("occurred-at-out-of-range");
        assertThat(dead.get("payload").toString()).contains("\"id\": \"" + id + "\"").doesNotContain("5208");
        assertThat(rows(id)).isZero();
        assertThat(jdbc.sql("select to_regclass('\"wqPrjTransaction_2199_01\"') is null").query(Boolean.class).single()).isTrue();
    }

    @Test
    void each_month_gets_its_own_partition() throws Exception {
        String id = nextId();
        publish(message("transfer-out.json", id, "1-" + id, "2025-03-31T23:59:59.999999Z"));

        awaitRow(id);
        String partition = jdbc.sql("""
                select tableoid::regclass::text from "wqPrjTransaction" where "sourceId" = :id
                """).param("id", id).query(String.class).single();
        assertThat(partition).isEqualTo("\"wqPrjTransaction_2025_03\"");
    }

    @Test
    void after_a_restart_consumption_resumes_after_the_stored_offset() throws Exception {
        byte[] poison = ("not json " + nextId()).getBytes(StandardCharsets.UTF_8);
        String sha = DeadLetters.sha256(poison);
        publish(poison);
        String before = nextId();
        publish(message("transfer-out.json", before, "1-" + before, "2026-09-05T00:00:00Z"));
        awaitRow(before);

        consumer.stop();
        consumer.start();
        String after = nextId();
        publish(message("transfer-out.json", after, "1-" + after, "2026-09-05T00:00:00Z"));
        awaitRow(after);

        // Re-reading from the start would dead-letter the poison message a second time.
        assertThat(jdbc.sql("select count(*) from \"wqDeadLetter\" where \"bodySha256\" = :sha")
                .param("sha", sha).query(Long.class).single()).isEqualTo(1);
        long stored = jdbc.sql("select \"streamOffset\" from \"wqIngestCursor\" where \"feed\" = :feed")
                .param("feed", STREAM).query(Long.class).single();
        assertThat(stored).isNotNegative();
    }

    // --- helpers ---

    private byte[] message(String fixture, String id, String ownerId, String created) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(StreamMessageDecoderTest.fixture(fixture));
        node.put("id", id);
        ((ObjectNode) node.get("userContext")).put("ownerId", ownerId);
        ((ObjectNode) node.get("createdContext")).put("created", Instant.parse(created).toString());
        return mapper.writeValueAsBytes(node);
    }

    private void publish(byte[] body) throws InterruptedException {
        CountDownLatch confirmed = new CountDownLatch(1);
        producer.send(producer.messageBuilder().addData(body).build(), status -> {
            if (status.isConfirmed()) {
                confirmed.countDown();
            }
        });
        assertThat(confirmed.await(10, TimeUnit.SECONDS)).as("broker confirmed").isTrue();
    }

    private void awaitRow(String sourceId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> rows(sourceId) == 1);
    }

    private long rows(String sourceId) {
        return jdbc.sql("select count(*) from \"wqPrjTransaction\" where \"sourceId\" = :id")
                .param("id", sourceId).query(Long.class).single();
    }

    private static String nextId() {
        return Long.toString(IDS.incrementAndGet());
    }
}
