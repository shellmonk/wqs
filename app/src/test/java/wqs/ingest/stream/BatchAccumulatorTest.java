package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionBatch;

class BatchAccumulatorTest {

    private static final Duration MAX_WAIT = Duration.ofMillis(200);

    @Test
    void flushes_when_max_size_is_reached() {
        MutableClock clock = new MutableClock(Instant.parse("2033-01-01T00:00:00Z"));
        BatchAccumulator accumulator = new BatchAccumulator("s", 3, MAX_WAIT, clock);

        accumulator.add(transaction("a"), 1);
        accumulator.add(transaction("b"), 2);
        assertThat(accumulator.drainIfReady()).isEmpty();

        accumulator.add(transaction("c"), 3);
        Optional<ProjectionBatch> batch = accumulator.drainIfReady();

        assertThat(batch).isPresent();
        assertThat(batch.get().transactions()).hasSize(3);
        assertThat(batch.get().committedOffset()).isEqualTo(3L);
        assertThat(accumulator.pending()).isZero();
    }

    @Test
    void flushes_when_the_wait_window_elapses_even_below_max_size() {
        MutableClock clock = new MutableClock(Instant.parse("2033-01-01T00:00:00Z"));
        BatchAccumulator accumulator = new BatchAccumulator("s", 500, MAX_WAIT, clock);

        accumulator.add(transaction("a"), 1);
        assertThat(accumulator.drainIfReady()).isEmpty();

        clock.advance(Duration.ofMillis(201));
        Optional<ProjectionBatch> batch = accumulator.drainIfReady();

        assertThat(batch).isPresent();
        assertThat(batch.get().transactions()).hasSize(1);
    }

    @Test
    void an_empty_accumulator_never_flushes() {
        MutableClock clock = new MutableClock(Instant.parse("2033-01-01T00:00:00Z"));
        BatchAccumulator accumulator = new BatchAccumulator("s", 3, MAX_WAIT, clock);

        clock.advance(Duration.ofSeconds(10));

        assertThat(accumulator.drainIfReady()).isEmpty();
        assertThat(accumulator.drainNow()).isEmpty();
    }

    @Test
    void the_committed_offset_is_the_highest_in_the_batch() {
        MutableClock clock = new MutableClock(Instant.parse("2033-01-01T00:00:00Z"));
        BatchAccumulator accumulator = new BatchAccumulator("s", 10, MAX_WAIT, clock);

        accumulator.add(transaction("a"), 7);
        accumulator.add(transaction("b"), 9);
        accumulator.add(transaction("c"), 8);

        ProjectionBatch batch = accumulator.drainNow().orElseThrow();
        assertThat(batch.committedOffset()).isEqualTo(9L);
        // Each transaction keeps its own offset, for its dead-letter row.
        assertThat(batch.transactionOffsets()).containsExactly(7L, 9L, 8L);
        accumulator.add(transaction("d"), 10);
        assertThat(accumulator.drainNow().orElseThrow().transactionOffsets()).containsExactly(10L);
    }

    @Test
    void the_wait_window_restarts_after_a_flush() {
        MutableClock clock = new MutableClock(Instant.parse("2033-01-01T00:00:00Z"));
        BatchAccumulator accumulator = new BatchAccumulator("s", 100, MAX_WAIT, clock);

        accumulator.add(transaction("a"), 1);
        clock.advance(Duration.ofMillis(201));
        accumulator.drainIfReady();

        accumulator.add(transaction("b"), 2);
        assertThat(accumulator.drainIfReady()).isEmpty();
    }

    private static ProjectionTransaction transaction(String sourceId) {
        return new ProjectionTransaction(
                1L, SourceKind.TRANSACTION, sourceId, Instant.parse("2033-01-01T00:00:00Z"),
                "P1", "instants", "b", "p", TxTypes.PLAY, BigDecimal.ONE, "EUR", null, null, null);
    }

    /** Minimal mutable clock; Clock.fixed cannot advance. */
    static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
