package wqs.stream;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.OffsetSpecification;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import wqs.config.StreamProperties;
import wqs.dto.IngestResult;
import wqs.dto.StreamBatchItem;
import wqs.dto.StreamBatchResult;
import wqs.dto.StreamMessage;
import wqs.exception.BatchItemRejectedException;
import wqs.exception.DecodeException;
import wqs.exception.RejectedException;
import wqs.service.StreamIngestService;
import wqs.service.TransactionIngestService;
import wqs.util.TransientFailures;

/**
 * Consumes {@code walAccountTransactions} from the RabbitMQ stream. Transactions are the service
 * layer's: each {@link StreamIngestService} call is ONE database transaction that also stores the
 * message's offset in {@code "wqIngestCursor"}, so a crash replays from the last committed message
 * and never skips one.
 *
 * <p>Messages are committed in batches. The client's dispatch thread only puts each message on a
 * bounded queue (a full queue blocks it, so the broker slows down instead of memory filling up);
 * one worker takes up to {@code batch-size} messages, or what arrived within {@code batch-max-wait},
 * and commits them with the last offset in one transaction. A refused message rolls the batch back
 * and that batch is handled one message per transaction; a transient failure retries the batch;
 * any other failure also falls back to one message per transaction. Messages queued but
 * not committed when stopping are read again from the stored offset on restart. The broker's own offset tracking is not used. A redelivered message hits the unique key and
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
    private volatile Thread worker;
    private volatile BlockingQueue<Received> queue;
    /** The last committed offset: set before subscribing, then advanced by the worker. */
    private volatile long lastOffset = -1;

    /** A message as received, before decoding. */
    record Received(long offset, byte[] body) {}

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
        queue = new ArrayBlockingQueue<>(2 * properties.batchSize());
        worker = Thread.ofVirtual().name("wqs-stream-batches").start(this::work);
        subscriber = Thread.ofVirtual().name("wqs-stream-subscribe").start(this::subscribe);
    }

    @Override
    public synchronized void stop() {
        running = false;
        // The subscriber may be sleeping in its retry backoff: interrupt it.
        join(subscriber, true);
        // The worker finishes the batch it is committing (an interrupt during database I/O would
        // break the connection) and then stops taking batches. Only after it has stopped is the
        // queue cleared, so no batch is ever committed past a gap in the offsets.
        join(worker, false);
        Consumer c = consumer;
        consumer = null;
        if (c != null) {
            c.close();
        }
        BlockingQueue<Received> q = queue;
        if (q != null) {
            q.clear(); // not committed: read again from the stored offset on restart
        }
    }

    private static void join(Thread t, boolean interrupt) {
        if (t == null) {
            return;
        }
        if (interrupt) {
            t.interrupt();
        }
        try {
            t.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        Received received = new Received(offset, bodyOf(message));
        try {
            // A full queue holds the broker back. Wait in short steps so stopping is never blocked;
            // a message dropped while stopping is read again from the stored offset on restart.
            while (running) {
                if (queue.offer(received, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The single worker: takes batches off the queue, in offset order, and commits them. */
    private void work() {
        while (running) {
            List<Received> batch;
            try {
                batch = nextBatch();
            } catch (InterruptedException e) {
                return;
            }
            if (batch.isEmpty()) {
                continue;
            }
            try {
                commit(batch);
            } catch (RuntimeException e) {
                // Never let one batch kill the only worker: handle its messages one by one.
                log.error("Batch at stream offsets {}..{} failed unexpectedly ({}); handling its messages one by one",
                        batch.getFirst().offset(), batch.getLast().offset(), e.getClass().getSimpleName());
                batch.stream().filter(r -> r.offset() > lastOffset).forEach(r -> handleOne(r.offset(), r.body()));
            }
        }
    }

    /** Waits for a first message, then for more until the batch is full or batch-max-wait has passed. */
    private List<Received> nextBatch() throws InterruptedException {
        Received first = queue.poll(1, TimeUnit.SECONDS);
        if (first == null) {
            return List.of();
        }
        List<Received> batch = new ArrayList<>(properties.batchSize());
        batch.add(first);
        long deadline = System.nanoTime() + properties.batchMaxWait().toNanos();
        while (batch.size() < properties.batchSize()) {
            if (queue.drainTo(batch, properties.batchSize() - batch.size()) > 0) {
                continue;
            }
            long left = deadline - System.nanoTime();
            Received next = left > 0 ? queue.poll(left, TimeUnit.NANOSECONDS) : null;
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }

    private void commit(List<Received> batch) {
        String feed = properties.name();
        List<StreamBatchItem> items = new ArrayList<>(batch.size());
        for (Received r : batch) {
            try {
                items.add(new StreamBatchItem.Readable(r.offset(), decoder.decode(r.body())));
            } catch (DecodeException e) {
                items.add(new StreamBatchItem.Unreadable(r.offset(), r.body(), e.getMessage()));
            } catch (RuntimeException e) {
                // A decoder bug, never a value: the message is unreadable, not a reason to stop.
                items.add(new StreamBatchItem.Unreadable(r.offset(), r.body(), "decoder failed: " + e.getClass().getSimpleName()));
            }
        }
        Duration backoff = Duration.ofMillis(100);
        while (running) {
            try {
                StreamBatchResult result = ingest.ingestBatch(feed, items);
                lastOffset = batch.getLast().offset();
                meters.summary("wqs.ingest.stream.batch.size").record(batch.size());
                count("stored", result.stored());
                count("already_stored", result.alreadyStored());
                count("dead_lettered", result.deadLettered());
                return;
            } catch (BatchItemRejectedException e) {
                // Rolled back. Refusals are rare: rather than re-running the batch once per refused
                // message, handle this batch one message per transaction.
                log.info("Stream offset {} refused ({}); handling batch {}..{} one by one",
                        e.offset(), e.rejection().reason(), batch.getFirst().offset(), batch.getLast().offset());
                batch.forEach(r -> handleOne(r.offset(), r.body()));
                return;
            } catch (RuntimeException e) {
                if (!TransientFailures.isTransient(e)) {
                    log.warn("Batch at stream offsets {}..{} failed ({}); handling its messages one by one",
                            batch.getFirst().offset(), batch.getLast().offset(), e.getClass().getSimpleName());
                    batch.forEach(r -> handleOne(r.offset(), r.body()));
                    return;
                }
                log.warn("Transient failure on batch at stream offset {} ({}); retrying in {}",
                        batch.getFirst().offset(), e.getClass().getSimpleName(), backoff);
                if (!sleep(backoff)) {
                    return; // stopping: nothing was committed, so the batch is read again on restart
                }
                backoff = min(backoff.multipliedBy(2), MAX_BACKOFF);
            }
        }
    }

    private void count(String outcome, int n) {
        if (n > 0) {
            meters.counter("wqs.ingest.stream.messages", "outcome", outcome).increment(n);
        }
    }

    /** The fallback: one message in its own transaction, with the per-message failure handling. */
    private void handleOne(long offset, byte[] body) {
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
                    return;
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
        } catch (RuntimeException e) {
            ingest.recordUnreadable(feed, offset, body, "unreadable", "decoder failed: " + e.getClass().getSimpleName());
            return "dead_lettered";
        }
        try {
            return ingest.ingest(feed, offset, m) == IngestResult.STORED ? "stored" : "already_stored";
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
