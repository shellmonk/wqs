package wqs.ingest.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import wqs.domain.ProjectionItem;
import wqs.projection.ProjectionBatch;

/**
 * Accumulates stream events and releases them as a batch on EITHER a size threshold or an
 * elapsed wait window, whichever comes first (spec 6.1). A short window -- around 200ms --
 * gives sub-second freshness while still collapsing counter updates per user per batch.
 *
 * Deliberately knows nothing about RabbitMQ so the batching rules can be tested with a fixed
 * clock and no broker. Not thread-safe: the stream is consumed sequentially by design.
 */
public class BatchAccumulator {

    private final String stream;
    private final int maxSize;
    private final Duration maxWait;
    private final Clock clock;

    private final List<ProjectionItem> pending = new ArrayList<>();
    private long highestOffset = -1;
    private Instant windowStart;

    public BatchAccumulator(String stream, int maxSize, Duration maxWait, Clock clock) {
        this.stream = stream;
        this.maxSize = maxSize;
        this.maxWait = maxWait;
        this.clock = clock;
    }

    public void add(ProjectionItem item, long offset) {
        if (pending.isEmpty()) {
            windowStart = clock.instant();
        }
        pending.add(item);
        highestOffset = Math.max(highestOffset, offset);
    }

    /** Flushes if the size threshold is met or the wait window has elapsed. */
    public Optional<ProjectionBatch> drainIfReady() {
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        boolean sizeReached = pending.size() >= maxSize;
        boolean waitElapsed = !Duration.between(windowStart, clock.instant()).minus(maxWait).isNegative();
        return sizeReached || waitElapsed ? drainNow() : Optional.empty();
    }

    /** Flushes unconditionally. Used on shutdown so nothing is left unprojected. */
    public Optional<ProjectionBatch> drainNow() {
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        ProjectionBatch batch = new ProjectionBatch(stream, List.copyOf(pending), highestOffset);
        pending.clear();
        highestOffset = -1;
        windowStart = null;
        return Optional.of(batch);
    }

    /** Discards everything and resets all state. Used when the consumer re-reads from the store. */
    public void clear() {
        pending.clear();
        highestOffset = -1;
        windowStart = null;
    }

    public int pending() {
        return pending.size();
    }
}
