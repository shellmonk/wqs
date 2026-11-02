package wqs.jobs;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code wqs.jobs.*}. Jobs are declared in configuration, not in code (spec 6.5). */
@ConfigurationProperties(prefix = "wqs")
public record JobProperties(Map<String, JobSpec> jobs) {

    public JobProperties {
        jobs = jobs == null ? Map.of() : Map.copyOf(jobs);
    }

    public record JobSpec(
            boolean enabled,
            String cron,
            Integer intervalSeconds,
            MisfirePolicy misfirePolicy,
            Map<String, String> params) {

        public JobSpec {
            params = params == null ? Map.of() : Map.copyOf(params);
            if (misfirePolicy == null) {
                misfirePolicy = MisfirePolicy.DO_NOTHING;
            }
            boolean hasCron = cron != null && !cron.isBlank();
            boolean hasInterval = intervalSeconds != null;
            if (hasCron == hasInterval) {
                throw new IllegalArgumentException(
                        "a job must declare exactly one of 'cron' or 'intervalSeconds'");
            }
        }

        public boolean isCronBased() {
            return cron != null && !cron.isBlank();
        }
    }

    /**
     * DO_NOTHING is correct for pollers: they catch up by advancing their own watermark, so
     * queued catch-up fires would only produce redundant polls (spec 6.5).
     */
    public enum MisfirePolicy {
        DO_NOTHING,
        FIRE_AND_PROCEED
    }
}
