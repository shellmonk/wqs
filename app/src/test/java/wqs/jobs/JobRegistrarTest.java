package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import wqs.jobs.JobProperties.JobSpec;
import wqs.jobs.JobProperties.MisfirePolicy;

class JobRegistrarTest {

    /** A job that does nothing; this test only inspects scheduler state. */
    public static class NoOpJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {}
    }

    private static final JobDefinition DEFINITION = new JobDefinition("demo", NoOpJob.class);
    private static final JobKey JOB_KEY = JobKey.jobKey("demo", JobRegistrar.GROUP);
    private static final TriggerKey TRIGGER_KEY = TriggerKey.triggerKey("demo", JobRegistrar.GROUP);

    private Scheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "test-" + System.nanoTime());
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        // Deliberately not started: reconciliation must not require a running scheduler.
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown(true);
    }

    @Test
    void registers_a_declared_job() throws Exception {
        registrar(spec("0 0 3 * * ?", null)).reconcile();

        assertThat(scheduler.checkExists(JOB_KEY)).isTrue();
        CronTrigger trigger = (CronTrigger) scheduler.getTrigger(TRIGGER_KEY);
        assertThat(trigger.getCronExpression()).isEqualTo("0 0 3 * * ?");
    }

    @Test
    void cron_triggers_run_in_utc_whatever_the_jvm_zone() throws Exception {
        java.util.TimeZone jvm = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            registrar(spec("0 0 3 * * ?", null)).reconcile();
        } finally {
            java.util.TimeZone.setDefault(jvm);
        }

        CronTrigger trigger = (CronTrigger) scheduler.getTrigger(TRIGGER_KEY);
        assertThat(trigger.getTimeZone().getID()).isEqualTo("UTC");
        java.time.Instant next = trigger.getFireTimeAfter(new Date()).toInstant();
        assertThat(next.atZone(java.time.ZoneOffset.UTC).getHour()).isEqualTo(3);
    }

    @Test
    void a_stored_cron_trigger_in_another_zone_is_rescheduled_to_utc() throws Exception {
        scheduler.addJob(org.quartz.JobBuilder.newJob(NoOpJob.class).withIdentity(JOB_KEY).storeDurably().build(), true);
        scheduler.scheduleJob(org.quartz.TriggerBuilder.newTrigger().withIdentity(TRIGGER_KEY).forJob(JOB_KEY)
                .withSchedule(org.quartz.CronScheduleBuilder.cronSchedule("0 0 3 * * ?")
                        .inTimeZone(java.util.TimeZone.getTimeZone("Europe/Belgrade"))
                        .withMisfireHandlingInstructionDoNothing())
                .build());

        registrar(spec("0 0 3 * * ?", null)).reconcile();

        assertThat(((CronTrigger) scheduler.getTrigger(TRIGGER_KEY)).getTimeZone().getID()).isEqualTo("UTC");
    }

    @Test
    void reschedules_rather_than_duplicating_when_the_cron_changes() throws Exception {
        registrar(spec("0 0 3 * * ?", null)).reconcile();

        registrar(spec("0 30 4 * * ?", null)).reconcile();

        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(JOB_KEY);
        assertThat(triggers).hasSize(1);
        assertThat(((CronTrigger) triggers.get(0)).getCronExpression()).isEqualTo("0 30 4 * * ?");
    }

    @Test
    void removes_a_job_that_has_been_disabled() throws Exception {
        registrar(spec("0 0 3 * * ?", null)).reconcile();

        JobSpec disabled = new JobSpec(false, "0 0 3 * * ?", null, MisfirePolicy.DO_NOTHING, Map.of());
        new JobRegistrar(scheduler, new JobProperties(Map.of("demo", disabled)), List.of(DEFINITION))
                .reconcile();

        assertThat(scheduler.checkExists(JOB_KEY)).isFalse();
    }

    @Test
    void removes_a_job_whose_definition_bean_is_no_longer_contributed() throws Exception {
        registrar(spec("0 0 3 * * ?", null)).reconcile();

        // The definition list -- not the config map -- is what's empty here: this is the
        // orphan path through removeOrphans(), not the disabled/missing-config path through the
        // main loop (that path is covered by removes_a_job_that_has_been_disabled()). Config for
        // "demo" is left in place to prove the definition's absence, not the config, is what
        // drives removal.
        new JobRegistrar(
                        scheduler,
                        new JobProperties(Map.of("demo", spec("0 0 3 * * ?", null))),
                        List.of())
                .reconcile();

        assertThat(scheduler.checkExists(JOB_KEY)).isFalse();
    }

    @Test
    void supports_interval_triggers_for_frequent_pollers() throws Exception {
        registrar(spec(null, 5)).reconcile();

        Trigger trigger = scheduler.getTrigger(TRIGGER_KEY);
        assertThat(trigger).isNotNull();
        assertThat(trigger).isNotInstanceOf(CronTrigger.class);
    }

    @Test
    void reschedules_rather_than_duplicating_when_the_interval_changes() throws Exception {
        registrar(spec(null, 5)).reconcile();

        registrar(spec(null, 10)).reconcile();

        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(JOB_KEY);
        assertThat(triggers).hasSize(1);
        SimpleTrigger trigger = (SimpleTrigger) triggers.get(0);
        assertThat(trigger.getRepeatInterval()).isEqualTo(10_000L);
    }

    @Test
    void reschedules_rather_than_duplicating_when_the_misfire_policy_changes() throws Exception {
        JobSpec doNothing = new JobSpec(true, "0 0 3 * * ?", null, MisfirePolicy.DO_NOTHING, Map.of());
        JobSpec fireAndProceed =
                new JobSpec(true, "0 0 3 * * ?", null, MisfirePolicy.FIRE_AND_PROCEED, Map.of());

        new JobRegistrar(scheduler, new JobProperties(Map.of("demo", doNothing)), List.of(DEFINITION))
                .reconcile();
        new JobRegistrar(
                        scheduler, new JobProperties(Map.of("demo", fireAndProceed)), List.of(DEFINITION))
                .reconcile();

        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(JOB_KEY);
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).getMisfireInstruction())
                .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW);
    }

    @Test
    void reconcile_does_not_reschedule_an_unchanged_trigger() throws Exception {
        JobRegistrar registrar = registrar(spec("0 0 3 * * ?", null));

        registrar.reconcile();
        Date firstStartTime = scheduler.getTrigger(TRIGGER_KEY).getStartTime();

        // buildTrigger() stamps a fresh start time of "now" on every call; a wrongly repeated
        // rescheduleJob would overwrite the stored trigger's start time with a later one, so an
        // unchanged start time after a second reconcile() is evidence no reschedule happened.
        Thread.sleep(5);
        registrar.reconcile();
        Date secondStartTime = scheduler.getTrigger(TRIGGER_KEY).getStartTime();

        assertThat(secondStartTime).isEqualTo(firstStartTime);
    }

    @Test
    void params_reach_the_job_data_map() throws Exception {
        JobSpec withParams = new JobSpec(
                true, "0 0 3 * * ?", null, MisfirePolicy.DO_NOTHING, Map.of("monthsAhead", "3"));
        new JobRegistrar(scheduler, new JobProperties(Map.of("demo", withParams)), List.of(DEFINITION))
                .reconcile();

        assertThat(scheduler.getJobDetail(JOB_KEY).getJobDataMap().getString("monthsAhead"))
                .isEqualTo("3");
    }

    @Test
    void rejects_a_spec_with_neither_cron_nor_interval() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new JobSpec(true, null, null, MisfirePolicy.DO_NOTHING, Map.of()));
    }

    private JobRegistrar registrar(JobSpec spec) {
        return new JobRegistrar(
                scheduler, new JobProperties(Map.of("demo", spec)), List.of(DEFINITION));
    }

    private static JobSpec spec(String cron, Integer intervalSeconds) {
        return new JobSpec(true, cron, intervalSeconds, MisfirePolicy.DO_NOTHING, Map.of());
    }
}
