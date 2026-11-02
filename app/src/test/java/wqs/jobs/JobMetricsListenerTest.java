package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/** I5: wqs.job.duration / outcome / misfires register and move. */
class JobMetricsListenerTest {

    public static class OkJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {}
    }

    public static class FailingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            throw new JobExecutionException("boom");
        }
    }

    private Scheduler scheduler;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private JobMetricsListener listener;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "metrics-" + System.nanoTime());
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        listener = new JobMetricsListener(scheduler, registry);
        scheduler.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown(true);
    }

    @Test
    void records_duration_and_outcome_per_job() throws Exception {
        run("ok-job", OkJob.class);
        run("bad-job", FailingJob.class);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(registry.find("wqs.job.outcome").tags("job", "ok-job", "result", "success")
                    .counter()).isNotNull().extracting(c -> c.count()).isEqualTo(1.0);
            assertThat(registry.find("wqs.job.outcome").tags("job", "bad-job", "result", "failure")
                    .counter()).isNotNull().extracting(c -> c.count()).isEqualTo(1.0);
        });
        assertThat(registry.find("wqs.job.duration").tag("job", "ok-job").timer().count()).isEqualTo(1);
    }

    @Test
    void counts_misfires_per_job() {
        Trigger trigger = TriggerBuilder.newTrigger().withIdentity("t", JobRegistrar.GROUP)
                .forJob("late-job", JobRegistrar.GROUP).startNow().build();

        listener.triggerMisfired(trigger);

        assertThat(registry.find("wqs.job.misfires").tag("job", "late-job").counter().count()).isEqualTo(1.0);
    }

    private void run(String name, Class<? extends Job> type) throws Exception {
        scheduler.scheduleJob(
                JobBuilder.newJob(type).withIdentity(name, JobRegistrar.GROUP).build(),
                TriggerBuilder.newTrigger().withIdentity(name, JobRegistrar.GROUP).startNow().build());
    }
}
