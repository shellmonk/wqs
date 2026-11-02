package wqs.projection;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Pending-cancel gauges (spec 11). Depth alone is not enough: a steady depth with a rising
 * oldest-age means cancels are arriving and never resolving, which is the failure the alarm in
 * spec 11.6 watches for.
 */
@Component
public class PendingCancelMetrics {

    public PendingCancelMetrics(PendingCancelStore store, MeterRegistry registry, Clock clock) {
        Gauge.builder("wqs.pendingcancel.depth", store, PendingCancelStore::depth)
                .description("Cancels parked awaiting their target item")
                .register(registry);

        Gauge.builder("wqs.pendingcancel.oldest.age.seconds", store,
                        s -> s.oldestParkedAt()
                                .map(parked -> (double) Duration.between(parked, clock.instant())
                                        .toSeconds())
                                .orElse(0d))
                .description("Age of the oldest parked cancel")
                .baseUnit("seconds")
                .register(registry);
    }
}
