package wqs.config;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import wqs.job.PartitionMaintenanceJob;

/** Quartz jobs. Boot registers every JobDetail and Trigger bean with the (clustered) scheduler. */
@Configuration
public class JobConfig {

    @Bean
    public JobDetail partitionMaintenanceJob() {
        return JobBuilder.newJob(PartitionMaintenanceJob.class)
                .withIdentity("partitionMaintenance")
                .withDescription("Creates the current and the next monthly partitions ahead of time")
                .storeDurably()
                .build();
    }

    /** At startup, then hourly. A missed run (all replicas down) simply runs on the next start. */
    @Bean
    public Trigger partitionMaintenanceTrigger(JobDetail partitionMaintenanceJob) {
        return TriggerBuilder.newTrigger()
                .forJob(partitionMaintenanceJob)
                .withIdentity("partitionMaintenance")
                .startNow()
                .withSchedule(SimpleScheduleBuilder.repeatHourlyForever().withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }
}
