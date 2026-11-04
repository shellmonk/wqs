package wqs.stream;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import wqs.dto.StreamBatchResult;

/** Every stream metric, in one place (see docs/operations.md). */
final class StreamMetrics {

    private final MeterRegistry meters;

    StreamMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** The gauges read the consumer's state when scraped. */
    void registerGauges(StreamConsumer consumer) {
        Gauge.builder("wqs.ingest.stream.leader", consumer, c -> c.state() == StreamConsumer.State.CONSUMING ? 1 : 0)
                .description("1 on the replica that consumes the stream").strongReference(true).register(meters);
        Gauge.builder("wqs.ingest.stream.lag", consumer, c -> c.lag().orElse(Double.NaN))
                .description("Messages in the stream not yet committed (leader only)").strongReference(true).register(meters);
        Gauge.builder("wqs.ingest.stream.last.commit.age", consumer, c -> secondsSince(c::lastCommitAt))
                .description("Seconds since the last committed batch (leader only)").baseUnit("seconds")
                .strongReference(true).register(meters);
    }

    void batchCommitted(int size, StreamBatchResult result) {
        meters.summary("wqs.ingest.stream.batch.size").record(size);
        messages(StreamOutcome.STORED, result.stored());
        messages(StreamOutcome.ALREADY_STORED, result.alreadyStored());
        messages(StreamOutcome.DEAD_LETTERED, result.deadLettered());
    }

    void messageHandled(StreamOutcome outcome) {
        messages(outcome, 1);
    }

    void lostToRetention(long count) {
        meters.counter("wqs.ingest.stream.lost.messages").increment(count);
    }

    private void messages(StreamOutcome outcome, int count) {
        if (count > 0) {
            meters.counter("wqs.ingest.stream.messages", "outcome", outcome.tag).increment(count);
        }
    }

    private static double secondsSince(Supplier<Instant> time) {
        Instant t = time.get();
        return t == null ? Double.NaN : Duration.between(t, Instant.now()).toMillis() / 1000.0;
    }
}
