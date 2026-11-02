package wqs.ingest.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param batchMaxSize   flush threshold in events
 * @param batchMaxWait   flush threshold in elapsed time; ~200ms gives sub-second freshness
 * @param maxAttempts    attempts before an event is dead-lettered (spec 6.4)
 * @param recoveryBackoff wait before re-subscribing from the stored offset after an unrecoverable failure
 */
@ConfigurationProperties(prefix = "wqs.stream")
public record StreamProperties(
        boolean enabled,
        String name,
        int batchMaxSize,
        Duration batchMaxWait,
        int maxAttempts,
        Duration recoveryBackoff) {

    public StreamProperties {
        if (name == null || name.isBlank()) {
            name = "wallet-transactions";
        }
        if (batchMaxSize <= 0) {
            batchMaxSize = 500;
        }
        if (batchMaxWait == null) {
            batchMaxWait = Duration.ofMillis(200);
        }
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (recoveryBackoff == null) {
            recoveryBackoff = Duration.ofSeconds(5);
        }
    }
}
