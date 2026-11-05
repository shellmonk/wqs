package wqs.stream;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.NoOffsetException;
import com.rabbitmq.stream.OffsetSpecification;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import wqs.config.StreamProperties;
import wqs.service.StreamIngestService;
import wqs.util.Backoff;

/**
 * Consumes {@code walAccountTransactions} from the RabbitMQ stream into the projection.
 *
 * <p>Every replica may enable this. Only the one holding the {@link StreamLeadership} lock
 * consumes; the others wait on standby and take over when it goes away. One "term" is the time a
 * replica holds the lock: it subscribes after the offset stored in {@code wqIngestCursors},
 * a {@link StreamBatcher} groups the messages, and a {@link BatchCommitter} commits each batch with
 * its last offset in one transaction. A crash or a lost lock replays from the last committed
 * offset and never skips a message; writes are idempotent, so a replay cannot double-count.
 *
 * <p>If the broker is unreachable, subscribing is retried in the background with backoff; the
 * application stays up for reads.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "wqs.stream.enabled", havingValue = "true")
public class StreamConsumer implements SmartLifecycle, BatchCommitter.Progress {

    private static final Duration STANDBY_POLL = Duration.ofSeconds(5);
    private static final Duration LEADERSHIP_CHECK = Duration.ofSeconds(5);
    private static final Duration FIRST_SUBSCRIBE_RETRY = Duration.ofSeconds(1);
    private static final Duration MAX_SUBSCRIBE_RETRY = Duration.ofSeconds(30);

    /** Where this replica's consumer is. */
    public enum State { STOPPED, STANDBY, CONNECTING, CONSUMING }

    private final Environment streamEnvironment;
    private final StreamProperties streamProperties;
    private final StreamMessageDecoder streamMessageDecoder;
    private final StreamIngestService streamIngestService;
    private final StreamLeadership streamLeadership;
    private final StreamMetrics streamMetrics;

    private volatile boolean running;
    /** True during a term: while this replica holds the lock and consumes. */
    private volatile boolean consuming;
    private volatile State state = State.STOPPED;
    private volatile long lastOffset = -1;
    private volatile Instant lastCommitAt;
    private volatile Thread supervisor;
    private volatile StreamBatcher batcher;
    private volatile Consumer streamClient;

    public StreamConsumer(Environment streamEnvironment,
                          StreamProperties streamProperties,
                          StreamMessageDecoder streamMessageDecoder,
                          StreamIngestService streamIngestService,
                          StreamLeadership streamLeadership,
                          MeterRegistry meterRegistry) {
        this.streamEnvironment = streamEnvironment;
        this.streamProperties = streamProperties;
        this.streamMessageDecoder = streamMessageDecoder;
        this.streamIngestService = streamIngestService;
        this.streamLeadership = streamLeadership;
        this.streamMetrics = new StreamMetrics(meterRegistry);
        streamMetrics.registerGauges(this);
    }

    // --- what the outside sees ---

    public State state() {
        return state;
    }

    /** The last committed offset (-1 before the first). */
    public long lastCommittedOffset() {
        return lastOffset;
    }

    public Instant lastCommitAt() {
        return lastCommitAt;
    }

    /**
     * Messages in the stream after the last committed one; empty when not consuming or the broker
     * cannot say. A lower bound: the broker reports the first offset of its last committed chunk.
     */
    public OptionalDouble lag() {
        if (state != State.CONSUMING) {
            return OptionalDouble.empty();
        }
        try {
            long head = streamEnvironment.queryStreamStats(streamProperties.name()).committedChunkId();
            return OptionalDouble.of(Math.max(0, head - lastOffset));
        } catch (NoOffsetException empty) {
            return OptionalDouble.of(0);
        } catch (RuntimeException e) {
            return OptionalDouble.empty();
        }
    }

    // --- BatchCommitter.Progress ---

    @Override
    public long lastOffset() {
        return lastOffset;
    }

    @Override
    public void committed(long offset) {
        lastOffset = offset;
        lastCommitAt = Instant.now();
    }

    // --- lifecycle ---

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        supervisor = Thread.ofVirtual().name("wqs-stream-supervisor").start(this::supervise);
    }

    /** The supervisor may be sleeping (standby, backoff, lock check): it is interrupted, ends the term and releases the lock. */
    @Override
    public synchronized void stop() {
        running = false;
        consuming = false;
        Thread t = supervisor;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state = State.STOPPED;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Takes the lock (or waits on standby), consumes while holding it, and starts over if it is lost. */
    private void supervise() {
        while (running) {
            if (!streamLeadership.tryAcquire()) {
                state = State.STANDBY;
                if (!sleep(STANDBY_POLL)) {
                    break;
                }
                continue;
            }
            try {
                consumeWhileLeader();
            } finally {
                // stop() interrupts this thread to wake it. Clear that while ending the term: closing
                // the subscription is a broker round trip and releasing the lock a database call,
                // and an interrupted thread would break both.
                boolean interrupted = Thread.interrupted();
                endTerm();
                streamLeadership.release();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        state = State.STOPPED;
    }

    private void consumeWhileLeader() {
        log.info("This replica holds the lock for stream {}: consuming", streamProperties.name());
        state = State.CONNECTING;
        consuming = true;
        BatchCommitter committer = new BatchCommitter(streamProperties.name(), streamMessageDecoder,
                streamIngestService, streamMetrics, this, () -> consuming);
        batcher = new StreamBatcher(streamProperties.batchSize(), streamProperties.batchMaxWait(),
                committer::commit, () -> consuming);
        batcher.start();
        if (!subscribe()) {
            return;
        }
        state = State.CONSUMING;
        while (sleep(LEADERSHIP_CHECK)) {
            if (!streamLeadership.stillHeld()) {
                log.error("Lost the lock for stream {}: stopping consumption on this replica", streamProperties.name());
                return;
            }
        }
    }

    /** Ends a term: the batcher finishes its current batch first, then the subscription closes. */
    private void endTerm() {
        consuming = false;
        StreamBatcher b = batcher;
        batcher = null;
        if (b != null) {
            b.stop();
        }
        Consumer c = streamClient;
        streamClient = null;
        if (c != null) {
            c.close();
        }
    }

    // --- subscribing ---

    /** Subscribes after the stored offset, retrying with backoff. False when the term ended first. */
    private boolean subscribe() {
        Backoff backoff = new Backoff(FIRST_SUBSCRIBE_RETRY, MAX_SUBSCRIBE_RETRY, () -> running && streamLeadership.stillHeld());
        while (running && consuming) {
            try {
                OptionalLong stored = streamIngestService.lastOffset(streamProperties.name());
                lastOffset = stored.orElse(-1);
                stored.ifPresent(this::detectRetentionGap);
                OffsetSpecification from = stored.isPresent()
                        ? OffsetSpecification.offset(stored.getAsLong() + 1)
                        : OffsetSpecification.first();
                streamClient = streamEnvironment.consumerBuilder()
                        .stream(streamProperties.name())
                        .offset(from)
                        .messageHandler((context, message) -> receive(context.offset(), message))
                        .build();
                log.info("Consuming stream {} after offset {}", streamProperties.name(), lastOffset);
                return true;
            } catch (RuntimeException e) {
                log.warn("Cannot subscribe to stream {} ({}); retrying in {}",
                        streamProperties.name(), e.toString(), backoff.next());
                if (!backoff.pause()) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Retention may have deleted messages this service never read (a long outage). They are gone,
     * and reading resumes at the oldest message left: that must be loud, never silent.
     */
    private void detectRetentionGap(long stored) {
        try {
            long first = streamEnvironment.queryStreamStats(streamProperties.name()).firstOffset();
            if (first > stored + 1) {
                long lost = first - stored - 1;
                log.error("Stream {}: {} messages (offsets {}..{}) were removed by retention before they were read; "
                        + "they are missing from the projection", streamProperties.name(), lost, stored + 1, first - 1);
                streamMetrics.lostToRetention(lost);
            }
        } catch (RuntimeException e) {
            // an empty stream, or stats unavailable: nothing to compare
        }
    }

    /** Runs on the stream client's dispatch thread, one message at a time, in offset order. */
    void receive(long offset, Message message) {
        if (offset <= lastOffset) {
            return; // already committed; the broker can resend the start of a chunk
        }
        StreamBatcher b = batcher;
        if (b != null) {
            b.offer(new ReceivedMessage(offset, bodyOf(message)));
        }
    }

    /**
     * For an AMQP value body (a non-Java producer) {@code getBodyAsBinary()} throws; an exception
     * on the client's thread would skip the message silently, so it becomes an unreadable body.
     */
    private static byte[] bodyOf(Message message) {
        try {
            return message.getBodyAsBinary();
        } catch (RuntimeException e) {
            Object raw = message.getBody();
            return raw == null ? new byte[0] : String.valueOf(raw).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** False when interrupted or stopping. */
    private boolean sleep(Duration d) {
        try {
            Thread.sleep(d);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
