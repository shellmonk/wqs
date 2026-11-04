package wqs.stream;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.OffsetSpecification;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import wqs.config.StreamProperties;
import wqs.dto.StreamMessage;
import wqs.exception.DecodeException;
import wqs.exception.RejectedException;
import wqs.service.StreamIngestService;
import wqs.service.TransactionIngestService;
import wqs.util.TransientFailures;

/**
 * Consumes {@code walAccountTransactions} from the RabbitMQ stream. Transactions are the service
 * layer's: each {@link StreamIngestService} call is ONE database transaction that also stores the
 * message's offset in {@code "wqIngestCursor"}, so a crash replays from the last committed message
 * and never skips one. The broker's own offset tracking is not used. A redelivered message hits the unique key and
 * is counted as a duplicate.
 *
 * <ul>
 *   <li>Unreadable message: dead-lettered (offset and body hash only), offset advanced.</li>
 *   <li>Readable but rejected ({@link RejectedException}: garbage timestamp, currency mismatch, ...):
 *       the ingest transaction rolls back, then the row is dead-lettered with its allowlisted fields
 *       and the offset advanced, in a fresh transaction.</li>
 *   <li>Any other non-transient failure (a constraint, a bug): dead-lettered as {@code write-failed}.</li>
 *   <li>Transient database failure: retried with backoff on the same message, never dead-lettered.</li>
 * </ul>
 *
 * <p>If the broker is unreachable at start-up, subscribing is retried in the background with
 * backoff; the application stays up. Exactly one replica may run this (wqs.stream.enabled).
 */
@Component
@ConditionalOnProperty(name = "wqs.stream.enabled", havingValue = "true")
public class StreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Environment environment;
    private final StreamProperties properties;
    private final StreamMessageDecoder decoder;
    private final StreamIngestService ingest;
    private final MeterRegistry meters;

    private volatile boolean running;
    private volatile Thread subscriber;
    private volatile Consumer consumer;
    /** The last offset handled; touched by the subscriber before subscribing, then only by the client's dispatch thread. */
    private volatile long lastOffset = -1;

    public StreamConsumer(Environment environment, StreamProperties properties, StreamMessageDecoder decoder,
                          StreamIngestService ingest, MeterRegistry meters) {
        this.environment = environment;
        this.properties = properties;
        this.decoder = decoder;
        this.ingest = ingest;
        this.meters = meters;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        subscriber = Thread.ofVirtual().name("wqs-stream-subscribe").start(this::subscribe);
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread t = subscriber;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Consumer c = consumer;
        consumer = null;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void subscribe() {
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try {
                OptionalLong stored = ingest.lastOffset(properties.name());
                lastOffset = stored.orElse(-1);
                OffsetSpecification from = stored.isPresent()
                        ? OffsetSpecification.offset(stored.getAsLong() + 1)
                        : OffsetSpecification.first();
                consumer = environment.consumerBuilder()
                        .stream(properties.name())
                        .offset(from)
                        .messageHandler((context, message) -> handle(context.offset(), message))
                        .build();
                log.info("Consuming stream {} after offset {}", properties.name(), lastOffset);
                return;
            } catch (RuntimeException e) {
                log.warn("Cannot subscribe to stream {} ({}); retrying in {}", properties.name(), e.toString(), backoff);
                if (!sleep(backoff)) {
                    return;
                }
                backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
            }
        }
    }

    /** Runs on the stream client's dispatch thread, one message at a time, in offset order. */
    void handle(long offset, Message message) {
        if (offset <= lastOffset) {
            return; // already committed; the broker can resend the start of a chunk
        }
        byte[] body = bodyOf(message);
        Duration backoff = Duration.ofMillis(100);
        while (running) {
            try {
                String outcome = process(offset, body);
                lastOffset = offset;
                meters.counter("wqs.ingest.stream.messages", "outcome", outcome).increment();
                return;
            } catch (RuntimeException e) {
                if (!TransientFailures.isTransient(e)) {
                    deadLetterFailedWrite(offset, body, e);
                    return;
                }
                log.warn("Transient failure at stream offset {} ({}); retrying in {}", offset, e.getClass().getSimpleName(), backoff);
                if (!sleep(backoff)) {
                    return; // stopping: nothing was committed, so the message is read again on restart
                }
                backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
            }
        }
    }

    private String process(long offset, byte[] body) {
        String feed = properties.name();
        StreamMessage m;
        try {
            m = decoder.decode(body);
        } catch (DecodeException e) {
            ingest.recordUnreadable(feed, offset, body, "unreadable", e.getMessage());
            return "dead_lettered";
        }
        try {
            return ingest.ingest(feed, offset, m) == TransactionIngestService.Outcome.INSERTED ? "inserted" : "duplicate";
        } catch (RejectedException e) {
            // The ingest transaction has rolled back; the rejection is recorded in a fresh one.
            ingest.recordRejected(feed, offset, m, e.reason(), e.getMessage());
            return "dead_lettered";
        }
    }

    /**
     * A readable message whose write failed for a non-transient reason (a constraint, a bug). It is
     * dead-lettered in a fresh transaction so the stream moves on. The error keeps only the
     * exception class and SQLState: a database message can quote the row's values.
     *
     * <p>Only a transient failure of this fallback is retried. Any other failure means even the
     * decoded fields cannot be stored, so the message is recorded by hash only. Nothing derived
     * from the message can keep this loop alive.
     */
    private void deadLetterFailedWrite(long offset, byte[] body, RuntimeException failure) {
        String feed = properties.name();
        String error = failure.getClass().getSimpleName() + sqlState(failure).map(s -> " SQLState " + s).orElse("");
        log.error("Stream offset {} dead-lettered: write failed ({})", offset, error);
        boolean withFields = true;
        Duration backoff = Duration.ofMillis(100);
        while (running) {
            boolean fields = withFields;
            try {
                if (fields) {
                    StreamMessage m = decoder.decode(body); // it decoded before, so it decodes again
                    ingest.recordRejected(feed, offset, m, "write-failed", error);
                } else {
                    ingest.recordUnreadable(feed, offset, body, "write-failed", error);
                }
                lastOffset = offset;
                meters.counter("wqs.ingest.stream.messages", "outcome", "dead_lettered").increment();
                return;
            } catch (RuntimeException e) {
                if (!TransientFailures.isTransient(e)) {
                    if (!fields) {
                        // Hash-only insert of fixed values failed: the database itself is broken.
                        log.error("Cannot record dead letter for stream offset {} ({}); retrying", offset, e.getClass().getSimpleName());
                    }
                    withFields = false;
                } else {
                    log.warn("Transient failure recording dead letter for stream offset {}; retrying", offset);
                }
                if (!sleep(backoff)) {
                    return;
                }
                backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
            }
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

    private static java.util.Optional<String> sqlState(Throwable t) {
        for (int depth = 0; t != null && depth < 32; depth++, t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return java.util.Optional.of(sql.getSQLState());
            }
        }
        return java.util.Optional.empty();
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

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
