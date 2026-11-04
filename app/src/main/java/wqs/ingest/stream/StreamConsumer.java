package wqs.ingest.stream;

import wqs.projection.source.StreamPayload;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wqs.domain.ProjectionTransaction;
import wqs.ingest.DeadLetterStore;
import wqs.ingest.TransientFailures;
import wqs.metrics.CachedGaugeValue;
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
    private static final Duration OFFSET_LAG_TTL = Duration.ofSeconds(15);

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
    /** When start() first ran; the lag gauge's floor until the first event is projected. */
    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final Counter projectedCounter;
    private final Counter duplicateCounter;
    private final Counter deadLetteredCounter;
    private final DistributionSummary batchSize;
    private final Timer batchDuration;

    /** True after an unrecoverable failure until poll() has re-subscribed from the stored offset. */
    private boolean recovering;
    private Instant recoverAt;
    /** Bumped on every (re)subscribe and on failure/stop; handlers of older generations are ignored. */
    private int generation;
    /** Set by stop(), cleared by start(): poll() and recovery are no-ops, so nothing re-subscribes after stop. */
    private boolean stopped;

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


        this.batchSize = DistributionSummary.builder("wqs.ingest.batch.size")
                .description("Events per applied stream batch")
                .register(registry);
        this.batchDuration = Timer.builder("wqs.ingest.batch.duration")
                .description("Time to apply one stream batch, including any per-transaction fallback")
                .register(registry);

        // Head minus committed. Cached: each scrape costs a broker round trip and a DB read. NaN
        // when the source cannot report a head (a test fake), never a reassuring 0.
        CachedGaugeValue offsetLag = new CachedGaugeValue(this::offsetLag, clock, OFFSET_LAG_TTL);
        // strongReference: Micrometer holds gauge state weakly, and nothing else holds this one.
        Gauge.builder("wqs.ingest.stream.offset.lag", offsetLag, CachedGaugeValue::get)
                .strongReference(true)
                .description("Stream head offset minus the committed offset (a lower bound: the "
                        + "head is the broker's last committed chunk id)")
                .register(registry);

        Gauge.builder("wqs.ingest.lag.seconds", this, StreamConsumer::lagSeconds)
                .tag("source", "stream")
                .description("Seconds between the last projected event's occurredAt and now; "
                        + "until something is projected, seconds since the consumer started")
                .register(registry);
    }

    /**
     * Every {@code source.close()} in this class runs OUTSIDE the monitor: a client close may wait
     * for an in-flight handler that is itself blocked on the monitor. The generation bump (done
     * under the lock, before the close) keeps any late delivery from the closing subscription inert.
     *
     * <p>Must not run concurrently with {@link #poll()}: poll's unlocked close could close the new
     * subscription. {@code StreamLifecycle} guarantees this by calling start() before the poll
     * scheduler exists.
     */
    public void start() {
        startedAt.compareAndSet(null, clock.instant());
        synchronized (this) {
            // Start from a clean slate: no leftover subscription, buffer or recovering state.
            generation++;
            accumulator.clear();
            recovering = false;
            stopped = false;
        }
        closeQuietly();
        synchronized (this) {
            if (!stopped) {
                // A failing subscribe (broker unreachable at startup) must not fail the app: it
                // enters the same recovery loop as a mid-run failure, and poll() retries from the
                // stored offset once the backoff has elapsed. An Error is rethrown after the state
                // is set (StreamLifecycle still installs the poller).
                try {
                    subscribe();
                } catch (Throwable e) {
                    enterRecovery(e);
                    rethrowIfError(e);
                }
            }
        }
    }

    /** Caller holds the monitor. */
    private void subscribe() {
        // Resume from the NEXT offset. Subscribing at the committed offset replays the last event.
        long from = offsetStore.read(properties.name()).map(o -> o + 1).orElse(0L);
        log.info("Subscribing to stream {} from offset {}", properties.name(), from);
        int gen = ++generation;
        source.subscribe(from, new StreamSource.StreamHandler() {
            @Override
            public void onMessage(StreamPayload payload, long offset) {
                StreamConsumer.this.onMessage(gen, payload, offset);
            }

            @Override
            public void onUndeserializable(byte[] body, long offset, Exception error) {
                StreamConsumer.this.onUndeserializable(gen, body, offset, error);
            }
        });
    }

    private void closeQuietly() {
        try {
            source.close();
        } catch (RuntimeException e) {
            // Safe: the generation bump makes any surviving subscription's messages inert.
            log.warn("Closing the source failed", e);
        }
    }

    private synchronized void onMessage(int gen, StreamPayload payload, long offset) {
        if (gen != generation || recovering || stopped) {
            return; // Stale subscription, or recovering: redelivered from the stored offset later.
        }
        try {
            ProjectionTransaction transaction;
            try {
                transaction = payloadMapper.toTransaction(payload);
            } catch (RuntimeException e) {
                if (TransientFailures.isTransient(e)) {
                    // Infrastructure (a pool timeout, a lost connection, a lock conflict): falls to the
                    // outer catch and retries via recovery, never dead-lettering.
                    throw e;
                }
                // Poison: bad input (the mapper and the domain reject it with IAE) or a bug -- each
                // would fail the same way on every retry and stall the stream for good. One rule for
                // every path: TransientFailures.
                deadLetterUnmappable(payload, offset, e);
                return;
            }
            accumulator.add(transaction, offset);
            accumulator.drainIfReady().ifPresent(this::applyBatch);
        } catch (Throwable e) {
            enterRecovery(e);
            rethrowIfError(e);
        }
    }

    private static void rethrowIfError(Throwable e) {
        if (e instanceof Error error) {
            throw error;
        }
    }

    /**
     * A malformed event is poison: record it from the raw payload. The offset is persisted only
     * when nothing earlier is still buffered; otherwise the next batch's higher offset covers it.
     * (If the process dies in between, the event is redelivered and recorded again: harmless.)
     */
    private void deadLetterUnmappable(StreamPayload payload, long offset, RuntimeException cause) {
        log.error("Cannot map event {} at offset {}; dead-lettering it",
                payload.id(), offset, cause);
        deadLetterStore.record(properties.name(), offset, payload, cause, 1);
        afterDeadLetter(offset);
    }

    /**
     * A body that could not even be parsed (see {@link RabbitStreamSource}) is poison too. It goes
     * through the same machinery as an unmappable event: same generation fence, same recovery on
     * failure, same offset rule. In particular this does NOT advance the stored offset when an
     * earlier event is still buffered -- the next batch's higher offset covers the poison row; if
     * the process dies first, the body is redelivered and recorded again, which is harmless.
     */
    private synchronized void onUndeserializable(int gen, byte[] body, long offset, Exception error) {
        if (gen != generation || recovering || stopped) {
            return;
        }
        try {
            log.error("Cannot deserialise the message at offset {}; dead-lettering the raw body", offset, error);
            deadLetterStore.record(properties.name(), offset, body, error, 1);
            afterDeadLetter(offset);
        } catch (Throwable e) {
            enterRecovery(e);
            rethrowIfError(e);
        }
    }

    /** Dead letter is durable. Persist the offset only if nothing earlier is still buffered. */
    private void afterDeadLetter(long offset) {
        deadLetteredCounter.increment();
        if (accumulator.pending() == 0) {
            offsetStore.write(properties.name(), offset);
        }
    }

    /** Flushes a partially filled batch whose wait window has elapsed. Called on a short timer. */
    public void poll() {
        synchronized (this) {
            if (stopped) {
                return;
            }
            if (!recovering) {
                try {
                    accumulator.drainIfReady().ifPresent(this::applyBatch);
                } catch (Throwable e) {
                    enterRecovery(e);
                    rethrowIfError(e);
                }
                return;
            }
            if (clock.instant().isBefore(recoverAt)) {
                return;
            }
        }
        // Backoff elapsed. Close on the timer thread, never on the client's dispatch thread and
        // never under the monitor. Handlers of the failed generation are already fenced.
        closeQuietly();
        synchronized (this) {
            if (stopped || !recovering) {
                return; // stop() or start() intervened while we were closing
            }
            recover();
        }
    }

    public void stop() {
        try {
            synchronized (this) {
                stopped = true;
                try {
                    if (!recovering) {
                        accumulator.drainNow().ifPresent(this::applyBatch);
                    }
                } catch (RuntimeException e) {
                    // Not advanced, so the batch is redelivered on the next start.
                    log.error("Final flush failed on stop; unflushed events will be redelivered", e);
                } finally {
                    generation++;
                }
            }
        } finally {
            closeQuietly(); // outside the monitor
        }
    }

    private void enterRecovery(Throwable cause) {
        log.error("Unrecoverable failure on stream {}; dropping the buffer and re-subscribing "
                + "from the stored offset after {}", properties.name(), properties.recoveryBackoff(), cause);
        // Only flip state here (this may run on the client's dispatch thread); poll() closes.
        generation++;
        accumulator.clear();
        recovering = true;
        recoverAt = clock.instant().plus(properties.recoveryBackoff());
    }

    private void recover() {
        recovering = false;
        try {
            subscribe();
        } catch (Throwable e) {
            enterRecovery(e);
            rethrowIfError(e);
        }
    }

    /**
     * Applies the batch whole; failing that, transaction by transaction so one poison event cannot discard its
     * neighbours; a transaction that still fails is dead-lettered. The batch offset is written only
     * once every transaction is projected or dead-lettered. Any exception escaping this method (retries
     * AND the dead-letter write failed, or the offset write failed) leaves the stored offset
     * untouched, and the caller enters recovery.
     */
    private void applyBatch(ProjectionBatch batch) {
        batchSize.record(batch.transactions().size());
        Timer.Sample sample = Timer.start(registry);
        try {
            applyBatchTimed(batch);
        } finally {
            sample.stop(batchDuration);
        }
    }

    private void applyBatchTimed(ProjectionBatch batch) {
        RuntimeException batchError = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                WriteResult result = writer.apply(batch);
                count(result);
                recordLag(batch.transactions());
                return;
            } catch (RuntimeException e) {
                batchError = e;
                log.warn("Batch attempt {}/{} failed for stream {} at offset {}",
                        attempt, properties.maxAttempts(), batch.stream(), batch.committedOffset(), e);
            }
        }
        log.warn("Batch of {} failed {} times on stream {}; applying transaction by transaction",
                batch.transactions().size(), properties.maxAttempts(), batch.stream(), batchError);

        List<ProjectionTransaction> projectedTransactions = new java.util.ArrayList<>();
        for (int i = 0; i < batch.transactions().size(); i++) {
            ProjectionTransaction transaction = batch.transactions().get(i);
            if (applyAlone(batch, transaction, batch.offsetOf(i))) {
                projectedTransactions.add(transaction);
            }
        }
        recordLag(projectedTransactions);

        if (batch.committedOffset() != null) {
            offsetStore.write(batch.stream(), batch.committedOffset());
        }
    }

    /**
     * The dead-letter row records the transaction's OWN offset (the batch's highest only if unknown) and
     * its TRUE attempt count: maxAttempts as part of the batch plus maxAttempts alone.
     */
    private boolean applyAlone(ProjectionBatch batch, ProjectionTransaction transaction, Long transactionOffset) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                // The transaction's own offset, so a rule rejection inside the writer records where it was.
                WriteResult result = writer.apply(new ProjectionBatch(
                        batch.stream(), List.of(transaction), null, transactionOffset == null ? null : List.of(transactionOffset)));
                count(result);
                return true;
            } catch (RuntimeException e) {
                if (TransientFailures.isTransient(e)) {
                    // A 40P01 deadlock with the concurrent delta poller, a lock or pool timeout: escapes,
                    // the consumer enters recovery and re-applies from the stored offset after the
                    // backoff -- retried, never dead-lettered (ruling I2). Anything else, a bug
                    // included, is dead-lettered below rather than looping in recovery (last fix T).
                    throw e;
                }
                lastError = e;
                log.warn("Transaction {} attempt {}/{} failed", transaction.sourceId(), attempt, properties.maxAttempts(), e);
            }
        }
        // If this write also fails the exception escapes: the offset must not move.
        Long offset = transactionOffset != null ? transactionOffset : batch.committedOffset();
        deadLetterStore.record(batch.stream(), offset, transaction, lastError, 2 * properties.maxAttempts());
        deadLetteredCounter.increment();
        log.error("Dead-lettered transaction {} (offset {}) on stream {}", transaction.sourceId(), offset, batch.stream());
        return false;
    }

    /**
     * Never a reassuring 0 while nothing has been projected: a consumer stuck from the start (a
     * poison loop, a dead broker) would otherwise look perfectly current. Until the first event
     * is projected the gauge reports time since the consumer started; before start() it is NaN.
     */
    double lagSeconds() {
        Instant reference = lastProjectedAt.get();
        if (reference == null) {
            reference = startedAt.get();
        }
        return reference == null ? Double.NaN : Duration.between(reference, clock.instant()).toMillis() / 1000d;
    }

    /** Clamped at 0: the head is a chunk's FIRST offset, so the committed offset may pass it. */
    double offsetLag() {
        java.util.OptionalLong head = source.headOffset();
        if (head.isEmpty()) {
            return Double.NaN;
        }
        long committed = offsetStore.read(properties.name()).orElse(-1L);
        return Math.max(0, head.getAsLong() - committed);
    }

    private void count(WriteResult result) {
        projectedCounter.increment(result.projected());
        duplicateCounter.increment(result.duplicates());
        deadLetteredCounter.increment(result.deadLettered());
    }

    private void recordLag(List<ProjectionTransaction> transactions) {
        transactions.stream().map(ProjectionTransaction::occurredAt).max(Instant::compareTo).ifPresent(lastProjectedAt::set);
    }
}
