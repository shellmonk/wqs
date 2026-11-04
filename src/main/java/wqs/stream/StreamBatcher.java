package wqs.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Turns the stream's one-at-a-time delivery into batches. The client's dispatch thread only
 * {@link #offer}s messages onto a bounded queue; a full queue blocks it, so the broker slows down
 * instead of memory filling up. One worker takes up to {@code batchSize} messages, or what arrived
 * within {@code maxWait}, and hands them to the {@code sink} in offset order.
 *
 * <p>One batcher per leadership term: {@link #stop()} lets the worker finish the batch it is
 * handing over (an interrupt during database I/O would break the connection), and only then drops
 * what is still queued, so no batch is ever committed past a gap in the offsets.
 */
final class StreamBatcher {

    private final int batchSize;
    private final Duration maxWait;
    private final Consumer<List<ReceivedMessage>> sink;
    private final BooleanSupplier active;
    private final BlockingQueue<ReceivedMessage> queue;
    private final Thread worker;

    StreamBatcher(int batchSize, Duration maxWait, Consumer<List<ReceivedMessage>> sink, BooleanSupplier active) {
        this.batchSize = batchSize;
        this.maxWait = maxWait;
        this.sink = sink;
        this.active = active;
        this.queue = new ArrayBlockingQueue<>(2 * batchSize);
        this.worker = Thread.ofVirtual().name("wqs-stream-batches").unstarted(this::work);
    }

    void start() {
        worker.start();
    }

    /**
     * Queues a message, waiting in short steps while the queue is full, so stopping is never
     * blocked. A message dropped because the term ended is read again by the next leader.
     */
    void offer(ReceivedMessage message) {
        try {
            while (active.getAsBoolean()) {
                if (queue.offer(message, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for the worker to finish its current batch, then drops what is still queued. The
     * caller must not be interrupted, or the wait would end at once (see StreamConsumer).
     */
    void stop() {
        try {
            worker.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
    }

    private void work() {
        while (active.getAsBoolean()) {
            List<ReceivedMessage> batch;
            try {
                batch = nextBatch();
            } catch (InterruptedException e) {
                return;
            }
            if (!batch.isEmpty()) {
                sink.accept(batch);
            }
        }
    }

    /** Waits for a first message, then for more until the batch is full or {@code maxWait} has passed. */
    private List<ReceivedMessage> nextBatch() throws InterruptedException {
        ReceivedMessage first = queue.poll(1, TimeUnit.SECONDS);
        if (first == null) {
            return List.of();
        }
        List<ReceivedMessage> batch = new ArrayList<>(batchSize);
        batch.add(first);
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (batch.size() < batchSize) {
            if (queue.drainTo(batch, batchSize - batch.size()) > 0) {
                continue;
            }
            long left = deadline - System.nanoTime();
            ReceivedMessage next = left > 0 ? queue.poll(left, TimeUnit.NANOSECONDS) : null;
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }
}
