package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PostgresTestBase;

/**
 * Boots the real Spring context with the clustered Quartz JobStore wired against the quartz
 * schema and performs an actual JobStore round trip, so a tablePrefix/schema misconfiguration
 * fails this test synchronously.
 *
 * QuartzSchemaTest alone cannot catch that class of error: it only queries catalog state over
 * raw JDBC and never starts the Scheduler bean. A misconfigured JobStore does not fail Spring
 * context startup either -- Quartz's cluster-checkin and misfire-handler threads merely log
 * repeating background ERRORs (confirmed by deliberately mistyping tablePrefix: the context
 * still started and scheduler.isStarted() still returned true). Only a query that the JobStore
 * must actually execute -- checkExists, here -- surfaces the error synchronously as a thrown
 * exception, which is what this test exercises.
 */
@SpringBootTest
@DirtiesContext
class QuartzSchedulerBootTest extends PostgresTestBase {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // PostgresTestBase's static initializer has already migrated this container with its
        // own Flyway instance; letting Spring Boot's auto-configured Flyway run again against
        // the same container on context startup is redundant at best.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    Scheduler scheduler;

    @Test
    void scheduler_round_trips_against_the_quartz_schema() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();

        // A real JobStore query -- effectively SELECT ... FROM quartz.QRTZ_JOB_DETAILS --
        // executed synchronously on this thread, not on a background thread. A wrong
        // tablePrefix or schema makes this throw immediately instead of merely logging.
        boolean exists = scheduler.checkExists(JobKey.jobKey("task-6-probe"));

        assertThat(exists).isFalse();
    }
}
