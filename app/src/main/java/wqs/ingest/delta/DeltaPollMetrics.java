package wqs.ingest.delta;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import wqs.metrics.CachedGaugeValue;
import wqs.projection.DeltaWatermarkStore;

/**
 * The poller-side delta meters (plan D10), pre-registered so a dashboard sees 0 rather than no
 * series. The writer-side ones ({@code wqs.delta.rows{outcome}}, {@code wqs.delta.stale}) are
 * {@link wqs.projection.DeltaMetrics}; a row the poller itself dead-letters (undecodable, or
 * failing alone in the writer) is added to the same {@code wqs.delta.rows{outcome=dead-lettered}}.
 *
 * <p>Named apart from {@code wqs.projection.DeltaMetrics} so the two beans do not collide.
 */
@Component
public class DeltaPollMetrics {

    public static final String ROWS_FETCHED = "wqs.delta.rows.fetched";
    public static final String PAGE_SATURATED = "wqs.delta.page.saturated";
    public static final String WATERMARK_AGE = "wqs.delta.watermark.age.seconds";
    public static final String TIMESTAMP_CLIFF = "wqs.delta.timestampcliff";
    public static final String POLL_FAILURES = "wqs.delta.poll.failures";
    public static final String OVERLAP_SATURATED = "wqs.delta.overlap.saturated";

    private static final Duration WATERMARK_AGE_TTL = Duration.ofSeconds(15);

    private final DistributionSummary rowsFetched;
    private final Counter saturated;
    private final Counter cliff;
    private final Counter failures;
    private final Counter deadLettered;
    private final Counter overlapSaturated;
    private final CachedGaugeValue watermarkAge;

    public DeltaPollMetrics(MeterRegistry registry, DeltaWatermarkStore watermarks, Clock clock) {
        this.rowsFetched = DistributionSummary.builder(ROWS_FETCHED)
                .description("Rows per fetched /wallet/delta page")
                .register(registry);
        this.saturated = Counter.builder(PAGE_SATURATED)
                .description("Pages that came back full (page-limit rows)")
                .register(registry);
        this.cliff = Counter.builder(TIMESTAMP_CLIFF)
                .description("Full pages whose rows all share one version: the cursor cannot advance (O-1)")
                .register(registry);
        this.failures = Counter.builder(POLL_FAILURES)
                .description("Delta poll runs that failed; the watermark was left untouched")
                .register(registry);
        this.overlapSaturated = Counter.builder(OVERLAP_SATURATED)
                .description("Full pages that did not advance the watermark (more than page-limit rows inside "
                        + "the overlap window), refetched from the watermark itself")
                .register(registry);
        this.deadLettered = Counter.builder(wqs.projection.DeltaMetrics.ROWS)
                .tag("outcome", "dead-lettered")
                .register(registry);
        // Computed on scrape from the stored watermark, so every node reports it, whichever node
        // ran the job; NaN until there is a watermark.
        this.watermarkAge = new CachedGaugeValue(
                () -> watermarks.read()
                        .map(w -> Duration.between(w, clock.instant()).toMillis() / 1000d)
                        .orElse(Double.NaN),
                clock, WATERMARK_AGE_TTL);
        Gauge.builder(WATERMARK_AGE, watermarkAge, CachedGaugeValue::get)
                .strongReference(true)
                .description("Seconds between now and the stored delta watermark")
                .register(registry);
    }

    void fetched(int rows) {
        rowsFetched.record(rows);
    }

    void saturated() {
        saturated.increment();
    }

    void cliff() {
        cliff.increment();
    }

    void failure() {
        failures.increment();
    }

    void overlapSaturated() {
        overlapSaturated.increment();
    }

    void deadLettered() {
        deadLettered.increment();
    }

    void watermarkMoved() {
        watermarkAge.invalidate();
    }
}
