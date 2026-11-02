package wqs.ingest.stream;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Starts, polls and stops the {@link StreamConsumer}. Calls {@code start()}, {@code poll()} and
 * {@code stop()} exactly; recovery (re-subscribing after a failure) is internal to the consumer
 * and driven by {@code poll()}, so this class never re-subscribes on its own.
 *
 * <p>Deliberately NOT a {@code SmartLifecycle}: that starts during context refresh, before
 * {@link ApplicationReadyEvent}, i.e. before
 * {@code PartitionRolloverService.ensureCurrentMonthOnStartup} has guaranteed that the current
 * month's partition exists (ruling R39). Inserting before then fails outright. The start listener
 * is {@code @Order(LOWEST_PRECEDENCE)}, strictly after that listener's {@code HIGHEST_PRECEDENCE}.
 */
public class StreamLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamLifecycle.class);
    private static final long POLLER_TERMINATION_SECONDS = 10;

    private final StreamConsumer consumer;
    private final Duration pollInterval;
    private ScheduledExecutorService scheduler;

    public StreamLifecycle(StreamConsumer consumer, Duration pollInterval) {
        this.consumer = consumer;
        this.pollInterval = pollInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        start();
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        consumer.start();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wqs-stream-poll");
            thread.setDaemon(true);
            return thread;
        });
        long millis = Math.max(1, pollInterval.toMillis());
        executor.scheduleWithFixedDelay(this::pollSafely, millis, millis, TimeUnit.MILLISECONDS);
        scheduler = executor;
    }

    /**
     * An exception (or a rethrown Error) escaping a periodic task cancels it forever, and recovery
     * is driven by poll(); so nothing, not even an Error, may escape here.
     */
    private void pollSafely() {
        try {
            consumer.poll();
        } catch (Throwable t) {
            log.error("Stream poll failed; will retry on the next tick", t);
        }
    }

    /** Stops the poller and waits for an in-flight poll, THEN stops the consumer. */
    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(POLLER_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Stream poller did not finish in {}s; interrupting", POLLER_TERMINATION_SECONDS);
                    scheduler.shutdownNow();
                    scheduler.awaitTermination(POLLER_TERMINATION_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        consumer.stop();
    }
}
