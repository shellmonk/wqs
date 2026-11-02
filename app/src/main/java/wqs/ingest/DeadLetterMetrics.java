package wqs.ingest;

import static wqs.db.generated.Tables.WQ_DEAD_LETTER;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import wqs.metrics.CachedGaugeValue;

/**
 * {@code wqs.deadletter.depth} (spec 11.2): rows in "wqDeadLetter". Computed on scrape and cached
 * ~60s like the runway gauge, so a scrape is not a COUNT every time. Rows are only ever removed
 * by an operator after replay, so a rising depth is the alarm, not the absolute value.
 */
@Component
public class DeadLetterMetrics {

    private static final Duration TTL = Duration.ofSeconds(60);

    public DeadLetterMetrics(DSLContext dsl, MeterRegistry registry, Clock clock) {
        CachedGaugeValue depth = new CachedGaugeValue(() -> dsl.fetchCount(WQ_DEAD_LETTER), clock, TTL);
        Gauge.builder("wqs.deadletter.depth", depth, CachedGaugeValue::get)
                .description("Events quarantined in wqDeadLetter")
                .register(registry);
    }
}
