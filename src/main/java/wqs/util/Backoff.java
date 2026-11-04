package wqs.util;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Pauses between retries, doubling each time up to a maximum. {@link #pause()} returns false when
 * the caller should give up instead of retrying: interrupted, or no longer {@code active}.
 */
public final class Backoff {

    private final Duration max;
    private final BooleanSupplier active;
    private Duration next;

    public Backoff(Duration initial, Duration max, BooleanSupplier active) {
        this.next = initial;
        this.max = max;
        this.active = active;
    }

    /** The pause the next {@link #pause()} will take. */
    public Duration next() {
        return next;
    }

    public boolean pause() {
        try {
            Thread.sleep(next);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        next = next.multipliedBy(2).compareTo(max) <= 0 ? next.multipliedBy(2) : max;
        return active.getAsBoolean();
    }
}
