package wqs.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Component;

/**
 * Job metrics of spec 11.2, for every job in the {@link JobRegistrar#GROUP} group:
 * {@code wqs.job.duration} (timer, tag job), {@code wqs.job.outcome} (counter, tags job and
 * result = success | failure) and {@code wqs.job.misfires} (counter, tag job).
 *
 * <p>Listeners are per scheduler instance, so in a cluster each node records the runs it
 * executed and the misfires its misfire handler detected; dashboards sum across nodes.
 */
@Component
public class JobMetricsListener extends TriggerListenerSupport implements JobListener {

    private final MeterRegistry registry;

    public JobMetricsListener(Scheduler scheduler, MeterRegistry registry) throws SchedulerException {
        this.registry = registry;
        scheduler.getListenerManager().addJobListener(this, GroupMatcher.jobGroupEquals(JobRegistrar.GROUP));
        scheduler.getListenerManager().addTriggerListener(this, GroupMatcher.triggerGroupEquals(JobRegistrar.GROUP));
    }

    @Override
    public String getName() {
        return "wqs-job-metrics";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {}

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {}

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        String job = context.getJobDetail().getKey().getName();
        Timer.builder("wqs.job.duration").tag("job", job).register(registry)
                .record(Duration.ofMillis(Math.max(0, context.getJobRunTime())));
        Counter.builder("wqs.job.outcome").tag("job", job)
                .tag("result", jobException == null ? "success" : "failure")
                .register(registry).increment();
    }

    @Override
    public void triggerMisfired(Trigger trigger) {
        Counter.builder("wqs.job.misfires").tag("job", trigger.getJobKey().getName())
                .register(registry).increment();
    }
}
