package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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
import wqs.db.PartitionedTable;
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
 *
 * The listener latch alone only proves execute() returned without throwing -- a gutted execute()
 * that stopped delegating to {@code PartitionRolloverService} would pass that just as well. Nor
 * can "the current month's partition exists" prove delegation: the startup listener
 * (ensureCurrentMonthOnStartup) already created it during context startup. So after startup and
 * before triggering, the test ensures (DROP IF EXISTS) a partition two months ahead
 * (inside the job's configured monthsAhead range, beyond what startup provisions),
 * asserts it is gone, and after the trigger asserts it is back with correct parentage. Only a real execute() -> rollOver() can recreate it.
 */
@SpringBootTest
@DirtiesContext
class PartitionRolloverJobBootTest extends PostgresTestBase {

    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("uuuu_MM");

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

        // Startup only provisions the first few months, and other tests may have created more, so
        // make sure one the job's rollOver covers is absent; only a delegating execute() can bring it back.
        YearMonth target = YearMonth.now(Clock.systemUTC()).plusMonths(2);
        String partitionName =
                PartitionedTable.WQ_WEB_ITEM.tableName() + "_" + target.format(MONTH_SUFFIX);
        db().execute("DROP TABLE IF EXISTS \"" + partitionName + "\"");
        assertThat(partitionAttached(partitionName)).as("precondition: dropped").isFalse();

        scheduler.triggerJob(jobKey);

        boolean fired = completed.await(10, TimeUnit.SECONDS);
        assertThat(fired).as("job neither completed nor errored within the timeout").isTrue();
        assertThat(thrown.get()).isNull();

        assertThat(partitionAttached(partitionName))
                .as("partition %s should be recreated by the rollover job", partitionName)
                .isTrue();
    }

    private static boolean partitionAttached(String partitionName) {
        return db().value("""
                select exists (select 1 from pg_inherits
                               where inhrelid = to_regclass(?)::oid and inhparent = to_regclass(?)::oid)
                """, Boolean.class, "\"" + partitionName + "\"",
                "\"" + PartitionedTable.WQ_WEB_ITEM.tableName() + "\"");
    }
}
