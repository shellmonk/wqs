package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.SchedulerListenerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PostgresTestBase;

/**
 * Proves Quartz can actually construct {@link PartitionRolloverJob}, not merely that the
 * context starts. The job's cron ("0 0 3 * * ?", see application.yaml) will not fire during a
 * test run, and registering a job with the scheduler (JobRegistrar.apply -- addJob/scheduleJob)
 * never instantiates it either; only a real trigger fire does, at which point Quartz calls
 * whatever JobFactory is wired. A missing SpringBeanJobFactory would throw
 * NoSuchMethodException there, invisibly to a test that never forces a fire. scheduler.triggerJob
 * forces exactly that fire on demand, so this test fails loudly if constructor injection is ever
 * broken, instead of relying on inspecting QuartzAutoConfiguration by hand.
 */
@SpringBootTest
@DirtiesContext
class PartitionRolloverJobBootTest extends PostgresTestBase {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    Scheduler scheduler;

    @Test
    void quartz_constructs_and_runs_the_job_via_spring_injection() throws Exception {
        JobKey jobKey = JobKey.jobKey("partition-rollover", JobRegistrar.GROUP);
        assertThat(scheduler.checkExists(jobKey)).isTrue();

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Exception> thrown = new AtomicReference<>();
        scheduler.getListenerManager().addJobListener(new JobListenerSupport() {
            @Override
            public String getName() {
                return "test-completion-listener";
            }

            @Override
            public void jobWasExecuted(JobExecutionContext context, JobExecutionException exception) {
                thrown.set(exception);
                completed.countDown();
            }
        });
        // A job that fails to construct (e.g. a non-Spring-aware JobFactory hitting
        // NoSuchMethodException) never reaches jobWasExecuted -- Quartz reports it here instead.
        scheduler.getListenerManager().addSchedulerListener(new SchedulerListenerSupport() {
            @Override
            public void schedulerError(String msg, SchedulerException cause) {
                thrown.set(cause);
                completed.countDown();
            }
        });

        scheduler.triggerJob(jobKey);

        boolean fired = completed.await(10, TimeUnit.SECONDS);
        assertThat(fired).as("job neither completed nor errored within the timeout").isTrue();
        assertThat(thrown.get()).isNull();
    }
}
