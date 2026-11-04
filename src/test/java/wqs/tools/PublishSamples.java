package wqs.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Publishes generated walAccountTransactions messages for one player to the local stream, shaped
 * like docs/reference/stream-message-samples.md: instants sessions of a bet and a prize per play,
 * plus transfers without a session, spread over the last 30 days.
 *
 * <p>{@code ./gradlew publishSamples [-Pcount=200] [-Pplayer=1717]}
 */
public final class PublishSamples {

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(args[0]);
        long player = Long.parseLong(args[1]);
        String uri = args[2];
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode transfer = (ObjectNode) mapper.readTree(fixture("transfer-out.json"));
        ObjectNode prize = (ObjectNode) mapper.readTree(fixture("instants-prize.json"));
        String run = UUID.randomUUID().toString().substring(0, 8);
        Instant start = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long step = Math.max(1, 30L * 24 * 3600 / Math.max(1, count));

        try (Environment env = Environment.builder().uri(uri).build()) {
            try {
                env.streamCreator().stream("wallet-transactions").create();
            } catch (RuntimeException alreadyThere) {
                // the stream exists
            }
            CountDownLatch confirmed = new CountDownLatch(count);
            try (Producer producer = env.producerBuilder().stream("wallet-transactions").build()) {
                for (int i = 0; i < count; i++) {
                    boolean sessioned = (i / 10) % 10 < 7;
                    ObjectNode m = (sessioned ? prize : transfer).deepCopy();
                    m.put("id", run + "-" + i);
                    ((ObjectNode) m.get("userContext")).put("ownerId", "1-" + player).put("issuerId", "1-" + player);
                    ((ObjectNode) m.get("createdContext")).put("created", start.plusSeconds(i * step).toString());
                    if (sessioned) {
                        boolean bet = i % 2 == 0;
                        m.put("type", bet ? "TT_BUY" : "PRIZE");
                        ((ObjectNode) m.get("amount")).put("amount", bet ? -2 : (i / 2) % 3 == 0 ? 10 : 0);
                        ((ObjectNode) m.get("batchContext"))
                                .put("batchId", "GS-" + run + "-" + (i / 10))
                                .put("subBatchId", "PS-" + run + "-" + (i / 2));
                    }
                    producer.send(producer.messageBuilder().addData(mapper.writeValueAsBytes(m)).build(),
                            status -> confirmed.countDown());
                }
                if (!confirmed.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the broker did not confirm every message");
                }
            }
        }
        System.out.printf("Published %d messages for player %d (run %s).%n", count, player, run);
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = PublishSamples.class.getResourceAsStream("/stream/" + name)) {
            return in.readAllBytes();
        }
    }

    private PublishSamples() {}
}
