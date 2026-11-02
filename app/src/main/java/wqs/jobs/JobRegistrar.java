package wqs.jobs;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import wqs.jobs.JobProperties.JobSpec;
import wqs.jobs.JobProperties.MisfirePolicy;

/**
 * Reconciles declared job configuration against stored Quartz state at startup.
 *
 * A clustered JobStore persists jobs and triggers, so a changed cron in application.yaml does
 * not take effect by itself -- the stored trigger wins. Without this reconciliation a schedule
 * change appears to be silently ignored (spec section 6.5).
 */
@Component
public class JobRegistrar {

    public static final String GROUP = "wqs";

    private static final Logger log = LoggerFactory.getLogger(JobRegistrar.class);

    private final Scheduler scheduler;
    private final JobProperties properties;
    private final List<JobDefinition> definitions;

    public JobRegistrar(Scheduler scheduler, JobProperties properties, List<JobDefinition> definitions) {
        this.scheduler = scheduler;
        this.properties = properties;
        this.definitions = definitions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() throws SchedulerException {
        Set<JobKey> declared = new HashSet<>();

        for (JobDefinition definition : definitions) {
            JobKey jobKey = JobKey.jobKey(definition.key(), GROUP);
            JobSpec spec = properties.jobs().get(definition.key());

            if (spec == null || !spec.enabled()) {
                if (scheduler.deleteJob(jobKey)) {
                    log.info("Removed job {} -- absent or disabled in configuration", jobKey);
                }
                continue;
            }

            declared.add(jobKey);
            apply(definition, jobKey, spec);
        }

        removeOrphans(declared);
    }

    private void apply(JobDefinition definition, JobKey jobKey, JobSpec spec) throws SchedulerException {
        JobDetail detail = JobBuilder.newJob(definition.jobClass())
                .withIdentity(jobKey)
                .storeDurably()
                .usingJobData(toJobDataMap(spec.params()))
                .build();

        TriggerKey triggerKey = TriggerKey.triggerKey(definition.key(), GROUP);
        Trigger desired = buildTrigger(triggerKey, jobKey, spec);

        // storeDurably + replace=true keeps params in sync even when the schedule is unchanged.
        scheduler.addJob(detail, true);

        if (scheduler.checkExists(triggerKey)) {
            if (hasChanged(scheduler.getTrigger(triggerKey), desired)) {
                scheduler.rescheduleJob(triggerKey, desired);
                log.info("Rescheduled job {}", jobKey);
            }
        } else {
            scheduler.scheduleJob(desired);
            log.info("Scheduled job {}", jobKey);
        }
    }

    /**
     * Compares the stored trigger against the trigger freshly built from current configuration.
     * Comparing trigger-to-trigger -- rather than re-deriving the comparison from individual
     * {@code JobSpec} fields -- keeps every property {@link #buildTrigger} consumes
     * automatically in scope, including the misfire instruction and any field added later, so
     * this cannot silently drift out of sync with {@link #buildTrigger} the way a field-by-field
     * comparison already has twice (cron-only, then interval-only).
     */
    private boolean hasChanged(Trigger existing, Trigger desired) {
        if (existing.getMisfireInstruction() != desired.getMisfireInstruction()) {
            return true;
        }
        if (desired instanceof CronTrigger desiredCron) {
            return !(existing instanceof CronTrigger existingCron)
                    || !existingCron.getCronExpression().equals(desiredCron.getCronExpression());
        }
        if (desired instanceof SimpleTrigger desiredSimple) {
            return !(existing instanceof SimpleTrigger existingSimple)
                    || existingSimple.getRepeatInterval() != desiredSimple.getRepeatInterval();
        }
        // buildTrigger only ever produces a CronTrigger or a SimpleTrigger.
        return true;
    }

    private Trigger buildTrigger(TriggerKey triggerKey, JobKey jobKey, JobSpec spec) {
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey);

        if (spec.isCronBased()) {
            CronScheduleBuilder cron = CronScheduleBuilder.cronSchedule(spec.cron());
            return builder.withSchedule(
                    spec.misfirePolicy() == MisfirePolicy.DO_NOTHING
                            ? cron.withMisfireHandlingInstructionDoNothing()
                            : cron.withMisfireHandlingInstructionFireAndProceed())
                    .build();
        }

        SimpleScheduleBuilder simple = SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(spec.intervalSeconds())
                .repeatForever();
        return builder.withSchedule(
                spec.misfirePolicy() == MisfirePolicy.DO_NOTHING
                        ? simple.withMisfireHandlingInstructionNextWithRemainingCount()
                        : simple.withMisfireHandlingInstructionFireNow())
                .startNow()
                .build();
    }

    private void removeOrphans(Set<JobKey> declared) throws SchedulerException {
        for (JobKey existing : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))) {
            if (!declared.contains(existing)) {
                scheduler.deleteJob(existing);
                log.info("Removed orphaned job {}", existing);
            }
        }
    }

    private static org.quartz.JobDataMap toJobDataMap(Map<String, String> params) {
        org.quartz.JobDataMap data = new org.quartz.JobDataMap();
        params.forEach(data::put);
        return data;
    }
}
