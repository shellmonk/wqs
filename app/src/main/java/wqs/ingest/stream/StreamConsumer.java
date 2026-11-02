package wqs.ingest.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wqs.domain.ProjectionItem;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionWriter;
import wqs.projection.StreamPayloadMapper;
import wqs.projection.WriteResult;

/**
 * Consumes the stream in adaptive batches (spec 6.1), applying one batch at a time. Batches are
 * applied strictly in offset order: with concurrent lanes over one stream, offsets complete out
 * of order, so only the contiguous acknowledged prefix could safely be committed. The documented
 * scale-out path is RabbitMQ super streams, one consumer per partition.
 *
 * <p>Threading model: the broker client calls {@link #onMessage} on its dispatch thread while
 * {@link #poll()} runs on a separate timer thread. Both mutate the non-thread-safe
 * {@link BatchAccumulator}, so {@code onMessage}, {@code poll} and {@code stop} are serialised
 * by this object's monitor.
 */
public class StreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    private final StreamSource source;
    private final StreamProperties properties;
    private final StreamPayloadMapper payloadMapper;
    private final ProjectionWriter writer;
    private final StreamOffsetStore offsetStore;
    private final MeterRegistry registry;
    private final Clock clock;

    private final BatchAccumulator accumulator;
    private final AtomicReference<Instant> lastProjectedAt = new AtomicReference<>();
    private final Counter projectedCounter;
    private final Counter duplicateCounter;

    public StreamConsumer(
            StreamSource source,
            StreamProperties properties,
            StreamPayloadMapper payloadMapper,
            ProjectionWriter writer,
            StreamOffsetStore offsetStore,
            MeterRegistry registry,
            Clock clock) {
        this.source = source;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
        this.writer = writer;
        this.offsetStore = offsetStore;
        this.registry = registry;
        this.clock = clock;
        this.accumulator = new BatchAccumulator(
                properties.name(), properties.batchMaxSize(), properties.batchMaxWait(), clock);

        this.projectedCounter = Counter.builder("wqs.ingest.events")
                .tag("source", "stream").tag("outcome", "projected").register(registry);
        this.duplicateCounter = Counter.builder("wqs.ingest.events")
                .tag("source", "stream").tag("outcome", "duplicate").register(registry);

        Gauge.builder("wqs.ingest.lag.seconds", lastProjectedAt, ref -> {
                    Instant last = ref.get();
                    return last == null ? 0d : Duration.between(last, clock.instant()).toMillis() / 1000d;
                })
                .tag("source", "stream")
                .description("Seconds between the last projected event's occurredAt and now")
                .register(registry);
    }

    public void start() {
        // Resume from the NEXT offset. Subscribing at the committed offset replays the last event.
        long from = offsetStore.read(properties.name()).map(o -> o + 1).orElse(0L);
        log.info("Subscribing to stream {} from offset {}", properties.name(), from);
        source.subscribe(from, this::onMessage);
    }

    private synchronized void onMessage(StreamPayload payload, long offset) {
        accumulator.add(payloadMapper.toItem(payload), offset);
        accumulator.drainIfReady().ifPresent(this::applyBatch);
    }

    /** Flushes a partially filled batch whose wait window has elapsed. Called on a short timer. */
    public synchronized void poll() {
        accumulator.drainIfReady().ifPresent(this::applyBatch);
    }

    public synchronized void stop() {
        accumulator.drainNow().ifPresent(this::applyBatch);
        source.close();
    }

    private void applyBatch(ProjectionBatch batch) {
        WriteResult result = writer.apply(batch);

        projectedCounter.increment(result.projected());
        duplicateCounter.increment(result.duplicates());

        batch.items().stream()
                .map(ProjectionItem::occurredAt)
                .max(Instant::compareTo)
                .ifPresent(lastProjectedAt::set);
    }
}
