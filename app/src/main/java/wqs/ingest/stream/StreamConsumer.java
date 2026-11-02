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
import java.util.List;
import wqs.domain.ProjectionItem;
import wqs.ingest.DeadLetterStore;
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
 *
 * <p>Safety invariant: the stored offset only advances past an event that was projected or
 * dead-lettered. If processing fails unrecoverably (for example the database is down), the
 * consumer never continues from memory: it drops its buffer, closes the source and, after a
 * backoff, re-subscribes from the stored offset, so a later batch cannot commit a higher offset
 * past events that exist nowhere.
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
    private final DeadLetterStore deadLetterStore;

    private final BatchAccumulator accumulator;
    private final AtomicReference<Instant> lastProjectedAt = new AtomicReference<>();
    private final Counter projectedCounter;
    private final Counter duplicateCounter;
    private final Counter deadLetteredCounter;

    /** True after an unrecoverable failure until poll() has re-subscribed from the stored offset. */
    private boolean recovering;
    private Instant recoverAt;

    public StreamConsumer(
            StreamSource source,
            StreamProperties properties,
            StreamPayloadMapper payloadMapper,
            ProjectionWriter writer,
            StreamOffsetStore offsetStore,
            MeterRegistry registry,
            Clock clock,
            DeadLetterStore deadLetterStore) {
        this.source = source;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
        this.writer = writer;
        this.offsetStore = offsetStore;
        this.registry = registry;
        this.clock = clock;
        this.deadLetterStore = deadLetterStore;
        this.accumulator = new BatchAccumulator(
                properties.name(), properties.batchMaxSize(), properties.batchMaxWait(), clock);

        this.projectedCounter = Counter.builder("wqs.ingest.events")
                .tag("source", "stream").tag("outcome", "projected").register(registry);
        this.duplicateCounter = Counter.builder("wqs.ingest.events")
                .tag("source", "stream").tag("outcome", "duplicate").register(registry);
        this.deadLetteredCounter = Counter.builder("wqs.ingest.events")
                .tag("source", "stream").tag("outcome", "deadlettered").register(registry);

        Gauge.builder("wqs.ingest.lag.seconds", lastProjectedAt, ref -> {
                    Instant last = ref.get();
                    return last == null ? 0d : Duration.between(last, clock.instant()).toMillis() / 1000d;
                })
                .tag("source", "stream")
                .description("Seconds between the last projected event's occurredAt and now")
                .register(registry);
    }

    public synchronized void start() {
        subscribe();
    }

    private void subscribe() {
        // Resume from the NEXT offset. Subscribing at the committed offset replays the last event.
        long from = offsetStore.read(properties.name()).map(o -> o + 1).orElse(0L);
        log.info("Subscribing to stream {} from offset {}", properties.name(), from);
        source.subscribe(from, this::onMessage);
    }

    private synchronized void onMessage(StreamPayload payload, long offset) {
        if (recovering) {
            return; // The source is closed; anything arriving now is redelivered after recovery.
        }
        try {
            ProjectionItem item;
            try {
                item = payloadMapper.toItem(payload);
            } catch (RuntimeException e) {
                deadLetterUnmappable(payload, offset, e);
                return;
            }
            accumulator.add(item, offset);
            accumulator.drainIfReady().ifPresent(this::applyBatch);
        } catch (RuntimeException e) {
            enterRecovery(e);
        }
    }

    /**
     * A malformed event is poison: record it from the raw payload. The offset is persisted only
     * when nothing earlier is still buffered; otherwise the next batch's higher offset covers it.
     * (If the process dies in between, the event is redelivered and recorded again: harmless.)
     */
    private void deadLetterUnmappable(StreamPayload payload, long offset, RuntimeException cause) {
        log.error("Cannot map event {} at offset {}; dead-lettering it",
                payload.transactionId(), offset, cause);
        deadLetterStore.record(properties.name(), offset, payload, cause, 1);
        deadLetteredCounter.increment();
        if (accumulator.pending() == 0) {
            offsetStore.write(properties.name(), offset);
        }
    }

    /** Flushes a partially filled batch whose wait window has elapsed. Called on a short timer. */
    public synchronized void poll() {
        if (recovering) {
            if (!clock.instant().isBefore(recoverAt)) {
                recover();
            }
            return;
        }
        try {
            accumulator.drainIfReady().ifPresent(this::applyBatch);
        } catch (RuntimeException e) {
            enterRecovery(e);
        }
    }

    public synchronized void stop() {
        try {
            if (!recovering) {
                accumulator.drainNow().ifPresent(this::applyBatch);
            }
        } catch (RuntimeException e) {
            // Not advanced, so the batch is redelivered on the next start.
            log.error("Final flush failed on stop; unflushed events will be redelivered", e);
        } finally {
            source.close();
        }
    }

    private void enterRecovery(RuntimeException cause) {
        log.error("Unrecoverable failure on stream {}; dropping the buffer and re-subscribing "
                + "from the stored offset after {}", properties.name(), properties.recoveryBackoff(), cause);
        try {
            source.close();
        } catch (RuntimeException e) {
            log.warn("Closing the source failed", e);
        }
        accumulator.clear();
        recovering = true;
        recoverAt = clock.instant().plus(properties.recoveryBackoff());
    }

    private void recover() {
        recovering = false;
        try {
            subscribe();
        } catch (RuntimeException e) {
            enterRecovery(e);
        }
    }

    /**
     * Applies the batch whole; failing that, item by item so one poison event cannot discard its
     * neighbours; an item that still fails is dead-lettered. The batch offset is written only
     * once every item is projected or dead-lettered. Any exception escaping this method (retries
     * AND the dead-letter write failed, or the offset write failed) leaves the stored offset
     * untouched, and the caller enters recovery.
     */
    private void applyBatch(ProjectionBatch batch) {
        RuntimeException batchError = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                WriteResult result = writer.apply(batch);
                projectedCounter.increment(result.projected());
                duplicateCounter.increment(result.duplicates());
                recordLag(batch.items());
                return;
            } catch (RuntimeException e) {
                batchError = e;
                log.warn("Batch attempt {}/{} failed for stream {} at offset {}",
                        attempt, properties.maxAttempts(), batch.stream(), batch.committedOffset(), e);
            }
        }
        log.warn("Batch of {} failed {} times on stream {}; applying item by item",
                batch.items().size(), properties.maxAttempts(), batch.stream(), batchError);

        List<ProjectionItem> projectedItems = new java.util.ArrayList<>();
        for (ProjectionItem item : batch.items()) {
            if (applyAlone(batch, item)) {
                projectedItems.add(item);
            }
        }
        recordLag(projectedItems);

        if (batch.committedOffset() != null) {
            offsetStore.write(batch.stream(), batch.committedOffset());
        }
    }

    private boolean applyAlone(ProjectionBatch batch, ProjectionItem item) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                WriteResult result = writer.apply(new ProjectionBatch(batch.stream(), List.of(item), null));
                projectedCounter.increment(result.projected());
                duplicateCounter.increment(result.duplicates());
                return true;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Item {} attempt {}/{} failed", item.sourceId(), attempt, properties.maxAttempts(), e);
            }
        }
        // If this write also fails the exception escapes: the offset must not move.
        deadLetterStore.record(batch.stream(), batch.committedOffset(), item, lastError, properties.maxAttempts());
        deadLetteredCounter.increment();
        log.error("Dead-lettered item {} (batch offset {}) on stream {}",
                item.sourceId(), batch.committedOffset(), batch.stream());
        return false;
    }

    private void recordLag(List<ProjectionItem> items) {
        items.stream().map(ProjectionItem::occurredAt).max(Instant::compareTo).ifPresent(lastProjectedAt::set);
    }
}
