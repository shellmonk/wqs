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
    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

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
                dsl().execute("drop table if exists \"" + name + "\"");
            }
        }
    }

    @Test
    void creates_the_current_month_and_the_requested_runway() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionRolloverService service =
                new PartitionRolloverService(new PartitionManager(dsl()), registry, FIXED);

        int created = service.rollOver(2);

        // June, July, August x 2 tables
        assertThat(created).isEqualTo(6);
    }

    @Test
    void exposes_runway_as_a_gauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionRolloverService service =
                new PartitionRolloverService(new PartitionManager(dsl()), registry, FIXED);

        service.rollOver(3);

        Gauge gauge = registry.find("wqs.partition.runway.months").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isGreaterThanOrEqualTo(4.0);
    }
}
