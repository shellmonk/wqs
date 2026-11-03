package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartitionRolloverServiceTest extends PostgresTestBase {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2032-06-10T12:00:00Z"), ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("uuuu_MM");

    // Both tests below exercise the same fixed month (2032-06) against the shared container
    // PostgresTestBase starts once per JVM, so without a reset they are order-dependent: whichever
    // runs second finds the partitions the first already created and asserts on a stale count.
    // Covers the widest window either test requests (rollOver(3) => 4 consecutive months).
    @BeforeEach
    void dropTestPartitions() {
        YearMonth start = YearMonth.from(FIXED.instant().atZone(ZoneOffset.UTC));
        for (int offset = 0; offset <= 3; offset++) {
            YearMonth month = start.plusMonths(offset);
            for (PartitionedTable table : PartitionedTable.values()) {
                String name = table.tableName() + "_" + month.format(MONTH_SUFFIX);
                db().execute("drop table if exists \"" + name + "\"");
            }
        }
    }

    @Test
    void creates_the_current_month_and_the_requested_runway() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionRolloverService service =
                new PartitionRolloverService(new PartitionManager(jdbc()), registry, FIXED);

        int created = service.rollOver(2);

        // June, July, August x 5 partitioned tables
        assertThat(created).isEqualTo(15);
    }

    @Test
    void exposes_runway_as_a_gauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionRolloverService service =
                new PartitionRolloverService(new PartitionManager(jdbc()), registry, FIXED);

        service.rollOver(3);

        Gauge gauge = registry.find("wqs.partition.runway.months").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isGreaterThanOrEqualTo(4.0);
    }

    /** I2: the gauge must follow the clock even when the rollover job never runs on this node. */
    @Test
    void the_runway_gauge_decreases_as_time_passes_without_the_job_running() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionManager manager = new PartitionManager(jdbc());
        manager.ensurePartitionsAhead(YearMonth.of(2032, 6), 3); // June..September, no job involved
        Instant[] now = {FIXED.instant()};
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        new PartitionRolloverService(manager, registry, moving);
        Gauge gauge = registry.find("wqs.partition.runway.months").gauge();
        double before = gauge.value();

        now[0] = now[0].plus(java.time.Duration.ofDays(31)); // July: one month of runway used up

        assertThat(before).isGreaterThanOrEqualTo(4.0);
        assertThat(gauge.value()).isEqualTo(before - 1);
    }
}
