package wqs.jobs;

import java.time.Duration;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import wqs.projection.PendingCancelResolution;

/**
 * Quartz wrapper only -- no logic (spec section 10): one {@link PendingCancelResolution#resolveOlderThan}
 * per fire, with the configured {@code thresholdDays} (plan D3). Concurrent execution is disallowed,
 * as for every WQS job. A failed run is a failed job; nothing is removed and the next fire retries.
 */
@DisallowConcurrentExecution
public class PendingCancelResolutionJob implements Job {

    private final PendingCancelResolution resolution;

    public PendingCancelResolutionJob(PendingCancelResolution resolution) {
        this.resolution = resolution;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            int thresholdDays = Integer.parseInt(context.getMergedJobDataMap().getString("thresholdDays"));
            resolution.resolveOlderThan(Duration.ofDays(thresholdDays));
        } catch (RuntimeException e) {
            throw new JobExecutionException(e, false);
        }
    }
}
