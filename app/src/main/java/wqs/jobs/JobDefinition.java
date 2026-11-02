package wqs.jobs;

import org.quartz.Job;

/**
 * Declares that a job class exists under a configuration key. Each job contributes one bean
 * of this type; {@link JobRegistrar} matches them against {@code wqs.jobs.*}.
 */
public record JobDefinition(String key, Class<? extends Job> jobClass) {}
