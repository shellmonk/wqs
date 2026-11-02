package wqs.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.DoubleSupplier;

/**
 * A gauge value computed on scrape, at most once per {@code ttl} of {@link Clock} time. For gauges
 * whose source is a query: computing on scrape means the value can never freeze when the code that
 * used to "set" it stops running (a job that does not fire on this node, a failed run), and the
 * TTL keeps a scrape from becoming a database round trip every time.
 *
 * <p>A failed computation is not cached and reads as NaN, so the next scrape retries.
 */
public final class CachedGaugeValue {

    private final DoubleSupplier source;
    private final Clock clock;
    private final Duration ttl;
    private double value = Double.NaN;
    private Instant computedAt;

    public CachedGaugeValue(DoubleSupplier source, Clock clock, Duration ttl) {
        this.source = source;
        this.clock = clock;
        this.ttl = ttl;
    }

    public synchronized double get() {
        Instant now = clock.instant();
        if (computedAt == null || !now.isBefore(computedAt.plus(ttl))) {
            try {
                value = source.getAsDouble();
                computedAt = now;
            } catch (RuntimeException e) {
                computedAt = null;
                return Double.NaN;
            }
        }
        return value;
    }

    /** Forces the next read to recompute, e.g. right after the underlying state changed. */
    public synchronized void invalidate() {
        computedAt = null;
    }
}
