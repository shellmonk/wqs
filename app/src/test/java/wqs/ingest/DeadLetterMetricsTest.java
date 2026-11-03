package wqs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import wqs.db.PostgresTestBase;

/** I5: wqs.deadletter.depth registers, counts rows and follows them after its cache expires. */
class DeadLetterMetricsTest extends PostgresTestBase {

    @Test
    void depth_counts_dead_letter_rows_and_refreshes_after_the_ttl() {
        dsl().execute("delete from \"wqDeadLetter\"");
        Instant[] now = {Instant.parse("2030-01-01T00:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DeadLetterMetrics(jdbc(), registry, clock);
        var gauge = registry.find("wqs.deadletter.depth").gauge();
        assertThat(gauge.value()).isZero();

        dsl().execute("insert into \"wqDeadLetter\" (\"stream\", \"source\", \"payload\", \"error\", \"attempts\") "
                + "values ('s', 1, '{}'::jsonb, 'e', 1), ('s', 1, '{}'::jsonb, 'e', 1)");
        assertThat(gauge.value()).isZero(); // cached
        now[0] = now[0].plus(Duration.ofSeconds(61));
        assertThat(gauge.value()).isEqualTo(2.0);
        dsl().execute("delete from \"wqDeadLetter\"");
    }
}
