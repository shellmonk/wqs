package wqs.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes one {@link JobDefinition} bean per job class, matched by {@link JobRegistrar}
 * against {@code wqs.jobs.*}. Kept inside {@code wqs.jobs} -- unlike the job beans themselves,
 * nothing outside this package may reference a job class (spec section 10).
 */
@Configuration
public class JobDefinitions {

    @Bean
    public JobDefinition partitionRolloverJobDefinition() {
        return new JobDefinition("partition-rollover", PartitionRolloverJob.class);
    }
}
