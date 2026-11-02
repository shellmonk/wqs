package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamLifecycleTest {

    @Test
    void a_throwing_poll_does_not_cancel_the_schedule() {
        StreamConsumer consumer = mock(StreamConsumer.class);
        AtomicInteger polls = new AtomicInteger();
        doAnswer(inv -> {
            int n = polls.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("boom");
            }
            if (n == 2) {
                throw new StackOverflowError("simulated Error");
            }
            return null;
        }).when(consumer).poll();

        StreamLifecycle lifecycle = new StreamLifecycle(consumer, Duration.ofMillis(10));
        lifecycle.start();
        try {
            // Polling continues past both a RuntimeException and an Error.
            await().atMost(Duration.ofSeconds(5)).until(() -> polls.get() >= 5);
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void stop_halts_and_awaits_the_poller_before_stopping_the_consumer() throws Exception {
        StreamConsumer consumer = mock(StreamConsumer.class);
        CountDownLatch pollEntered = new CountDownLatch(1);
        AtomicBoolean pollRunning = new AtomicBoolean();
        AtomicBoolean pollRanDuringConsumerStop = new AtomicBoolean();
        AtomicInteger pollsAfterStop = new AtomicInteger();
        AtomicBoolean consumerStopped = new AtomicBoolean();

        doAnswer(inv -> {
            if (consumerStopped.get()) {
                pollsAfterStop.incrementAndGet();
            }
            pollRunning.set(true);
            pollEntered.countDown();
            Thread.sleep(150); // a poll still in flight when stop() is called
            pollRunning.set(false);
            return null;
        }).when(consumer).poll();
        doAnswer(inv -> {
            pollRanDuringConsumerStop.set(pollRunning.get());
            consumerStopped.set(true);
            return null;
        }).when(consumer).stop();

        StreamLifecycle lifecycle = new StreamLifecycle(consumer, Duration.ofMillis(10));
        lifecycle.start();
        assertThat(pollEntered.await(5, TimeUnit.SECONDS)).isTrue();
        lifecycle.stop();
        Thread.sleep(100);

        assertThat(consumerStopped).isTrue();
        assertThat(pollRanDuringConsumerStop).isFalse();
        assertThat(pollsAfterStop.get()).isZero();
    }

    @Test
    void stop_is_idempotent_and_safe_before_start() {
        StreamConsumer consumer = mock(StreamConsumer.class);
        StreamLifecycle lifecycle = new StreamLifecycle(consumer, Duration.ofMillis(10));

        lifecycle.stop();
        lifecycle.stop();
    }
}
