package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import wqs.db.PostgresTestBase;
import wqs.ingest.delta.FakeDeltaEndpoint;

/** The {@code deltaPoll} job's registration (plan D9, spec 6.5). */
class DeltaPollJobBootTest extends PostgresTestBase {

    private static final JobKey JOB = JobKey.jobKey("delta-poll", JobRegistrar.GROUP);

    private static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest
    class ByDefault {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
        }

        @Autowired Scheduler scheduler;

        @Test
        void job_is_disabled_by_default() throws Exception {
            assertThat(scheduler.checkExists(JOB)).isFalse();
        }
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest
    @DirtiesContext
    class WhenEnabled {

        static final FakeDeltaEndpoint ENDPOINT = new FakeDeltaEndpoint();

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
            registry.add("wqs.jobs.delta-poll.enabled", () -> "true");
            registry.add("wqs.delta.base-url", ENDPOINT::baseUrl);
        }

        @Autowired Scheduler scheduler;

        @AfterEach
        void tearDown() throws Exception {
            // The JobStore is shared by every cached context: leave no enabled trigger behind.
            scheduler.deleteJob(JOB);
            ENDPOINT.close();
        }

        @Test
        void job_registers_with_do_nothing_misfire_when_enabled() throws Exception {
            JobDetail detail = scheduler.getJobDetail(JOB);
            assertThat(detail).isNotNull();
            assertThat(detail.getJobClass()).isEqualTo(DeltaPollJob.class);
            assertThat(DeltaPollJob.class.isAnnotationPresent(DisallowConcurrentExecution.class)).isTrue();
            assertThat(detail.isConcurrentExectionDisallowed()).isTrue();

            SimpleTrigger trigger = (SimpleTrigger) scheduler.getTrigger(TriggerKey.triggerKey("delta-poll", JobRegistrar.GROUP));
            assertThat(trigger.getRepeatInterval()).isEqualTo(5_000L);
            assertThat(trigger.getMisfireInstruction())
                    .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);

            // Quartz constructs the job through Spring and it polls the configured endpoint.
            await().atMost(Duration.ofSeconds(15)).until(() -> !ENDPOINT.queries().isEmpty());
            assertThat(ENDPOINT.queries().get(0)).startsWith("from=");
        }
    }
}
