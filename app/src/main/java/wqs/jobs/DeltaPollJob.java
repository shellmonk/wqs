package wqs.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import wqs.ingest.delta.DeltaPoller;

/**
 * Quartz wrapper only -- no logic (spec section 10): one {@link DeltaPoller#runOnce} per fire
 * (plan D9). Concurrent execution is disallowed cluster-wide, so there is one sequential delta
 * writer (D8). A failed run is a failed job (wqs.job.outcome{result=failure}); the watermark is
 * untouched and the next fire retries.
 *
 * <p>Lives in {@code wqs.jobs}, not beside the poller: nothing outside this package may reference a
 * job class (ArchitectureTest.nothing_depends_on_job_classes), and {@link JobDefinitions} must.
 */
@DisallowConcurrentExecution
public class DeltaPollJob implements Job {

    private final DeltaPoller poller;

    public DeltaPollJob(DeltaPoller poller) {
        this.poller = poller;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            poller.runOnce();
        } catch (RuntimeException e) {
            throw new JobExecutionException(e, false);
        }
    }
}
