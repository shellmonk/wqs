package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.SchedulerListenerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionWriter;

/**
 * The {@code pending-cancel-resolution} job (plan D3): registered by default, daily, with a 7-day
 * threshold from configuration, and a forced fire really reaches the resolution -- an 8-day-old parked
 * cancel is gone afterwards and a 1-day-old one is not.
 */
@SpringBootTest
@DirtiesContext
class PendingCancelResolutionJobBootTest extends PostgresTestBase {

    private static final JobKey JOB = JobKey.jobKey("pending-cancel-resolution", JobRegistrar.GROUP);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired Scheduler scheduler;
    @Autowired ProjectionWriter writer;
    @Autowired PartitionManager partitionManager;

    @Test
    void job_is_enabled_by_default_daily_with_a_seven_day_threshold() throws Exception {
        JobDetail detail = scheduler.getJobDetail(JOB);
        assertThat(detail).isNotNull();
        assertThat(detail.getJobClass()).isEqualTo(PendingCancelResolutionJob.class);
        assertThat(PendingCancelResolutionJob.class.isAnnotationPresent(DisallowConcurrentExecution.class)).isTrue();

        CronTrigger trigger = (CronTrigger) scheduler.getTrigger(TriggerKey.triggerKey(JOB.getName(), JobRegistrar.GROUP));
        assertThat(trigger.getCronExpression()).isEqualTo("0 30 3 * * ?");
        assertThat(trigger.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        assertThat(detail.getJobDataMap().getString("thresholdDays")).isEqualTo("7");
    }

    @Test
    void a_forced_fire_resolves_the_parked_cancels_older_than_the_threshold() throws Exception {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2035, 4), 0);
        clearProjection();
        writer.apply(new ProjectionBatch("s", List.of(cancel("c-old"), cancel("c-new")), 1L));
        db().execute("update \"wqPrjPendingCancel\" set \"parkedAt\" = now() - interval '8 days' where \"cancelSourceId\" = 'c-old'");
        db().execute("update \"wqPrjPendingCancel\" set \"parkedAt\" = now() - interval '1 day' where \"cancelSourceId\" = 'c-new'");

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Exception> thrown = new AtomicReference<>();
        scheduler.getListenerManager().addJobListener(new JobListenerSupport() {
            @Override
            public String getName() {
                return "pending-cancel-resolution-test-listener";
            }

            @Override
            public void jobWasExecuted(JobExecutionContext context, JobExecutionException exception) {
                if (context.getJobDetail().getKey().equals(JOB)) {
                    thrown.set(exception);
                    completed.countDown();
                }
            }
        });
        scheduler.getListenerManager().addSchedulerListener(new SchedulerListenerSupport() {
            @Override
            public void schedulerError(String msg, SchedulerException cause) {
                thrown.set(cause);
                completed.countDown();
            }
        });

        scheduler.triggerJob(JOB);

        assertThat(completed.await(10, TimeUnit.SECONDS)).as("job neither completed nor errored").isTrue();
        assertThat(thrown.get()).isNull();
        assertThat(db().column("select \"cancelSourceId\" from \"wqPrjPendingCancel\"", String.class))
                .containsExactly("c-new");
    }

    private static ProjectionTransaction cancel(String id) {
        return new ProjectionTransaction(
                5L, SourceKind.TRANSACTION, id, Instant.parse("2035-04-02T10:00:00Z"), Products.id(3), Products.type(3),
                null, null, TxTypes.CANCEL, new BigDecimal("-5.0000"), "EUR", null, "never-" + id, null);
    }
}
