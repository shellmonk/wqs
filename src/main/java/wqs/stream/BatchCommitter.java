package wqs.stream;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wqs.dto.IngestResult;
import wqs.dto.StreamBatchItem;
import wqs.dto.StreamMessage;
import wqs.exception.BatchItemRejectedException;
import wqs.exception.DecodeException;
import wqs.exception.RejectedException;
import wqs.service.StreamIngestService;
import wqs.util.Backoff;
import wqs.util.TransientFailures;

/**
 * Commits stream messages, each batch in ONE transaction with its last offset. When a batch cannot
 * commit as a whole, its messages are committed one by one, and a message that cannot be written
 * at all is dead-lettered, so the stream always moves on:
 *
 * <ol>
 *   <li><b>Batch</b>: all messages and the last offset in one transaction. A transient database
 *       failure retries the batch with backoff.</li>
 *   <li><b>One by one</b>, when a message of the batch is rejected (rare) or the batch fails for any
 *       other reason: each message in its own transaction. Unreadable and rejected messages are
 *       dead-lettered there.</li>
 *   <li><b>Dead letter</b>, when one message's write fails for a non-transient reason (a
 *       constraint, a bug): recorded with its allowlisted fields, or by hash only if even that fails.</li>
 * </ol>
 *
 * <p>Every loop stops when the leadership term ends ({@code active} turns false): nothing
 * uncommitted is lost, it is read again from the stored offset by the next leader.
 */
final class BatchCommitter {

    private static final Logger log = LoggerFactory.getLogger(BatchCommitter.class);
    private static final Duration FIRST_RETRY = Duration.ofMillis(100);
    private static final Duration MAX_RETRY = Duration.ofSeconds(30);

    /** Where the committer reports progress: the consumer's last committed offset. */
    interface Progress {
        long lastOffset();

        void committed(long offset);
    }

    private final String feed;
    private final StreamMessageDecoder decoder;
    private final StreamIngestService ingest;
    private final StreamMetrics metrics;
    private final Progress progress;
    private final BooleanSupplier active;

    BatchCommitter(String feed, StreamMessageDecoder decoder, StreamIngestService ingest, StreamMetrics metrics,
                   Progress progress, BooleanSupplier active) {
        this.feed = feed;
        this.decoder = decoder;
        this.ingest = ingest;
        this.metrics = metrics;
        this.progress = progress;
        this.active = active;
    }

    /** Commits a batch, falling back to one message per transaction when it cannot commit whole. */
    void commit(List<ReceivedMessage> batch) {
        try {
            commitBatch(batch);
        } catch (RuntimeException e) {
            // Never let one batch stop the only worker.
            log.error("Batch at stream offsets {}..{} failed unexpectedly ({}); handling its messages one by one",
                    batch.getFirst().offset(), batch.getLast().offset(), e.getClass().getSimpleName());
            commitOneByOne(batch);
        }
    }

    private void commitBatch(List<ReceivedMessage> batch) {
        List<StreamBatchItem> items = batch.stream().map(this::decode).toList();
        Backoff backoff = new Backoff(FIRST_RETRY, MAX_RETRY, active);
        while (active.getAsBoolean()) {
            try {
                ingest.preparePartitions(items); // before the batch transaction: see PartitionDdlService
                var result = ingest.ingestBatch(feed, items);
                progress.committed(batch.getLast().offset());
                metrics.batchCommitted(batch.size(), result);
                return;
            } catch (BatchItemRejectedException e) {
                log.info("Stream offset {} rejected ({}); handling batch {}..{} one by one",
                        e.offset(), e.rejection().reason(), batch.getFirst().offset(), batch.getLast().offset());
                commitOneByOne(batch);
                return;
            } catch (RuntimeException e) {
                if (!TransientFailures.isTransient(e)) {
                    log.warn("Batch at stream offsets {}..{} failed ({}); handling its messages one by one",
                            batch.getFirst().offset(), batch.getLast().offset(), e.getClass().getSimpleName());
                    commitOneByOne(batch);
                    return;
                }
                log.warn("Transient failure on batch at stream offset {} ({}); retrying in {}",
                        batch.getFirst().offset(), e.getClass().getSimpleName(), backoff.next());
                if (!backoff.pause()) {
                    return;
                }
            }
        }
    }

    private void commitOneByOne(List<ReceivedMessage> batch) {
        batch.stream().filter(m -> m.offset() > progress.lastOffset()).forEach(this::commitOne);
    }

    private void commitOne(ReceivedMessage received) {
        Backoff backoff = new Backoff(FIRST_RETRY, MAX_RETRY, active);
        while (active.getAsBoolean()) {
            try {
                StreamOutcome outcome = writeOne(received);
                progress.committed(received.offset());
                metrics.messageHandled(outcome);
                return;
            } catch (RuntimeException e) {
                if (!TransientFailures.isTransient(e)) {
                    deadLetterFailedWrite(received, e);
                    return;
                }
                log.warn("Transient failure at stream offset {} ({}); retrying in {}",
                        received.offset(), e.getClass().getSimpleName(), backoff.next());
                if (!backoff.pause()) {
                    return;
                }
            }
        }
    }

    /** One message in its own transaction: projected, or dead-lettered as unreadable or rejected. */
    private StreamOutcome writeOne(ReceivedMessage received) {
        StreamBatchItem item = decode(received);
        if (item instanceof StreamBatchItem.Unreadable unreadable) {
            ingest.recordUnreadable(feed, received.offset(), received.body(), "unreadable", unreadable.error());
            return StreamOutcome.DEAD_LETTERED;
        }
        StreamMessage message = ((StreamBatchItem.Readable) item).message();
        try {
            return ingest.ingestOne(feed, received.offset(), message) == IngestResult.STORED
                    ? StreamOutcome.STORED : StreamOutcome.ALREADY_STORED;
        } catch (RejectedException e) {
            // The ingest transaction has rolled back; the rejection is recorded in a fresh one.
            ingest.recordRejected(feed, received.offset(), message, e.reason(), e.getMessage());
            return StreamOutcome.DEAD_LETTERED;
        }
    }

    /**
     * A readable message whose write failed for a non-transient reason. It is dead-lettered in a
     * fresh transaction so the stream moves on. The error keeps only the exception class and
     * SQLState: a database message can quote the row's values. If even its decoded fields cannot be
     * stored, it is recorded by hash only; only transient failures are retried, so nothing derived
     * from the message can keep this loop alive.
     */
    private void deadLetterFailedWrite(ReceivedMessage received, RuntimeException failure) {
        String error = failure.getClass().getSimpleName() + sqlState(failure).map(s -> " SQLState " + s).orElse("");
        log.error("Stream offset {} dead-lettered: write failed ({})", received.offset(), error);
        boolean withFields = true;
        Backoff backoff = new Backoff(FIRST_RETRY, MAX_RETRY, active);
        while (active.getAsBoolean()) {
            try {
                if (withFields) {
                    StreamMessage message = decoder.decode(received.body()); // it decoded before, so it decodes again
                    ingest.recordRejected(feed, received.offset(), message, "write-failed", error);
                } else {
                    ingest.recordUnreadable(feed, received.offset(), received.body(), "write-failed", error);
                }
                progress.committed(received.offset());
                metrics.messageHandled(StreamOutcome.DEAD_LETTERED);
                return;
            } catch (RuntimeException e) {
                if (TransientFailures.isTransient(e)) {
                    log.warn("Transient failure recording dead letter for stream offset {}; retrying", received.offset());
                } else {
                    if (!withFields) {
                        // The hash-only insert of fixed values failed: the database itself is broken.
                        log.error("Cannot record dead letter for stream offset {} ({}); retrying",
                                received.offset(), e.getClass().getSimpleName());
                    }
                    withFields = false;
                }
                if (!backoff.pause()) {
                    return;
                }
            }
        }
    }

    /** Decodes a message; an unexpected decoder failure (a bug, never a value) makes it unreadable too. */
    private StreamBatchItem decode(ReceivedMessage received) {
        try {
            return new StreamBatchItem.Readable(received.offset(), decoder.decode(received.body()));
        } catch (DecodeException e) {
            return new StreamBatchItem.Unreadable(received.offset(), received.body(), e.getMessage());
        } catch (RuntimeException e) {
            return new StreamBatchItem.Unreadable(received.offset(), received.body(),
                    "decoder failed: " + e.getClass().getSimpleName());
        }
    }

    private static Optional<String> sqlState(Throwable t) {
        for (int depth = 0; t != null && depth < 32; depth++, t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return Optional.of(sql.getSQLState());
            }
        }
        return Optional.empty();
    }
}
