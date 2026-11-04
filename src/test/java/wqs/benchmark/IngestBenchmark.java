package wqs.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.IntegrationTest;
import wqs.config.StreamProperties;
import wqs.service.StreamIngestService;
import wqs.stream.StreamConsumer;
import wqs.stream.StreamLeadership;
import wqs.stream.StreamMessageDecoder;

/**
 * Stream ingest throughput, one message per transaction against batches of 500. Each run reads its
 * own pre-loaded stream (20,000 messages: 70% in sessions of 10, the rest without one) into Postgres
 * and is timed until its offset is committed. Run with {@code ./gradlew benchmark}; writes
 * build/benchmark/ingest-benchmark.md.
 */
@Tag("benchmark")
class IngestBenchmark extends IntegrationTest {

    static final int MESSAGES = 20_000;

    @Autowired Environment streams;
    @Autowired StreamMessageDecoder decoder;
    @Autowired StreamIngestService ingest;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("writerDataSource") javax.sql.DataSource writer;

    @Test
    void measure() throws Exception {
        StringBuilder report = new StringBuilder("# Ingest benchmark\n\n")
                .append(MESSAGES).append(" stream messages (70% in sessions of 10) into Postgres 16 in a container.\n\n")
                .append("| batch size | seconds | messages / s |\n|---:|---:|---:|\n");
        for (int batchSize : new int[] {1, 500}) {
            String stream = "bench-" + batchSize + "-" + UUID.randomUUID();
            preload(stream, batchSize);
            StreamProperties properties = new StreamProperties(true, "unused", stream, null, batchSize, Duration.ofMillis(200));
            StreamConsumer consumer = new StreamConsumer(streams, properties, decoder, ingest,
                    new StreamLeadership(writer, properties), new SimpleMeterRegistry());
            long t0 = System.nanoTime();
            consumer.start();
            while (offset(stream) < MESSAGES - 1) {
                Thread.sleep(50);
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            consumer.stop();
            report.append("| ").append(batchSize).append(" | ").append("%.1f".formatted(seconds)).append(" | ")
                    .append(Math.round(MESSAGES / seconds)).append(" |\n");
        }
        Path out = Path.of("build/benchmark/ingest-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private void preload(String stream, int run) throws Exception {
        streams.streamCreator().stream(stream).create();
        ObjectNode transfer = (ObjectNode) mapper.readTree(fixture("transfer-out.json"));
        ObjectNode prize = (ObjectNode) mapper.readTree(fixture("instants-prize.json"));
        CountDownLatch confirmed = new CountDownLatch(MESSAGES);
        try (Producer producer = streams.producerBuilder().stream(stream).build()) {
            for (int i = 0; i < MESSAGES; i++) {
                boolean sessioned = (i / 10) % 10 < 7;
                ObjectNode m = (sessioned ? prize : transfer).deepCopy();
                long player = 7_000_000L + run * 100_000L + (i / 100);
                m.put("id", run + "-" + i);
                m.put("type", sessioned && i % 2 == 0 ? "TT_BUY" : sessioned ? "PRIZE" : "TT_TRANSFER_OUT");
                ((ObjectNode) m.get("amount")).put("amount", i % 2 == 0 ? -2 : 5);
                ((ObjectNode) m.get("userContext")).put("ownerId", "1-" + player);
                ((ObjectNode) m.get("createdContext")).put("created", Instant.parse("2026-10-01T00:00:00Z").plusSeconds(i).toString());
                if (sessioned) {
                    ((ObjectNode) m.get("batchContext")).put("batchId", "S-" + run + "-" + (i / 10)).put("subBatchId", "P-" + run + "-" + (i / 2));
                }
                producer.send(producer.messageBuilder().addData(mapper.writeValueAsBytes(m)).build(), status -> confirmed.countDown());
            }
            if (!confirmed.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("broker did not confirm the preload");
            }
        }
    }

    private long offset(String stream) {
        return jdbc.sql("select coalesce(max(\"streamOffset\"), -1) from \"wqIngestCursor\" where \"feed\" = :feed")
                .param("feed", stream).query(Long.class).single();
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = IngestBenchmark.class.getResourceAsStream("/stream/" + name)) {
            return in.readAllBytes();
        }
    }
}
