package wqs.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code wqs.ingest.skipped{reason}} (spec 4.1, plan D8): events deliberately not projected.
 * Both reasons are registered up front, so a dashboard sees 0 rather than no series.
 * <ul>
 *   <li>{@code non-player}: the owner's userType is not 1. Nothing at all is written.</li>
 *   <li>{@code zero-amount}: recorded in "wqItemLocator" only, counted once on first sight
 *       (a re-delivery is a duplicate).</li>
 * </ul>
 */
public final class IngestSkipMetrics {

    public static final String NAME = "wqs.ingest.skipped";

    private final Counter nonPlayer;
    private final Counter zeroAmount;

    public IngestSkipMetrics(MeterRegistry registry) {
        this.nonPlayer = counter(registry, "non-player");
        this.zeroAmount = counter(registry, "zero-amount");
    }

    private static Counter counter(MeterRegistry registry, String reason) {
        return Counter.builder(NAME)
                .tag("reason", reason)
                .description("Events deliberately not projected, by reason")
                .register(registry);
    }

    public void nonPlayer() {
        nonPlayer.increment();
    }

    public void zeroAmount(int count) {
        zeroAmount.increment(count);
    }
}
