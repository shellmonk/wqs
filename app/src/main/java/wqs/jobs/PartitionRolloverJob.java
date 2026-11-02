package wqs.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import wqs.db.PartitionRolloverService;

/**
 * Quartz wrapper only -- no logic (spec section 10). Two concurrent rollovers would race on
 * CREATE TABLE, so concurrent execution is disallowed as required for every WQS job.
 */
@DisallowConcurrentExecution
public class PartitionRolloverJob implements Job {

    private final PartitionRolloverService service;

    public PartitionRolloverJob(PartitionRolloverService service) {
        this.service = service;
    }

    @Override
    public void execute(JobExecutionContext context) {
        int monthsAhead = Integer.parseInt(
                context.getMergedJobDataMap().getString("monthsAhead"));
        service.rollOver(monthsAhead);
    }
}
