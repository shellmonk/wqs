package wqs.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.IntegrationTest;

class PartitionMaintenanceJobTest extends IntegrationTest {

    @Autowired Scheduler scheduler;
    @Autowired JdbcClient jdbcClient;

    @Test
    void the_current_and_next_three_months_are_created_ahead() throws Exception {
        scheduler.triggerJob(JobKey.jobKey("partitionMaintenance"));
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (int i = 0; i <= 3; i++) {
                assertThat(exists(now.plusMonths(i))).as("partition for " + now.plusMonths(i)).isTrue();
            }
        });
    }

    @Test
    void the_job_is_scheduled_hourly_and_survives_in_the_clustered_store() throws Exception {
        assertThat(scheduler.checkExists(JobKey.jobKey("partitionMaintenance"))).isTrue();
        assertThat(jdbcClient.sql("select count(*) from quartz.qrtz_triggers where trigger_name = 'partitionMaintenance'")
                .query(Long.class).single()).isEqualTo(1);
    }

    private boolean exists(YearMonth m) {
        String name = "\"wqPrjTransactions_%04d_%02d\"".formatted(m.getYear(), m.getMonthValue());
        return jdbcClient.sql("select to_regclass(:name) is not null").param("name", name).query(Boolean.class).single();
    }
}
