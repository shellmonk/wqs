package wqs.job;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;
import wqs.repository.TransactionRepository;
import wqs.service.PartitionService;

/**
 * Keeps the current and the next few monthly partitions of {@code "wqPrjTransactions"} created, so
 * ingest rarely runs partition DDL itself. Quartz is clustered: one replica runs it at a time.
 */
@DisallowConcurrentExecution
public class PartitionMaintenanceJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);

    private final PartitionService partitionService;

    public PartitionMaintenanceJob(PartitionService partitionService) {
        this.partitionService = partitionService;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        int months = partitionService.ensureAhead(TransactionRepository.TABLE);
        log.debug("Partition maintenance: {} months of {} present", months, TransactionRepository.TABLE);
    }
}
