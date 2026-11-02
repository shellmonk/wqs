package wqs.db;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps monthly partitions provisioned ahead of now, and publishes how much runway remains.
 *
 * A failed rollover is the highest-severity SILENT failure in the design: nothing is wrong until
 * midnight on the first of the month, at which point every insert fails. The gauge is therefore
 * alarmed below 2 months (spec section 11.6).
 */
@Service
public class PartitionRolloverService {

    private static final Logger log = LoggerFactory.getLogger(PartitionRolloverService.class);
    private static final int STARTUP_MONTHS_AHEAD = 1;

    private final PartitionManager partitionManager;
    private final Clock clock;
    private final AtomicInteger runway = new AtomicInteger();

    public PartitionRolloverService(
            PartitionManager partitionManager, MeterRegistry registry, Clock clock) {
        this.partitionManager = partitionManager;
        this.clock = clock;

        Gauge.builder("wqs.partition.runway.months", runway, AtomicInteger::get)
                .description("Consecutive months of partitions provisioned from the current month")
                .register(registry);
    }

    /** Ensures partitions exist from the current month through {@code monthsAhead}. */
    public int rollOver(int monthsAhead) {
        YearMonth current = YearMonth.now(clock);
        int created = partitionManager.ensurePartitionsAhead(current, monthsAhead);
        int remaining = partitionManager.runwayMonths(current);
        runway.set(remaining);

        if (created > 0) {
            log.info("Created {} partitions; runway is now {} months", created, remaining);
        }
        return created;
    }

    /**
     * Inserting into a partitioned table with no matching partition fails outright, so the
     * current month must exist before ingestion starts rather than at the first cron fire.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureCurrentMonthOnStartup() {
        rollOver(STARTUP_MONTHS_AHEAD);
    }
}
