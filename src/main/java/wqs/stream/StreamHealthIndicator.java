package wqs.stream;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The stream consumer in /actuator/health. DOWN only when this replica is the leader and stuck:
 * messages are waiting but nothing has been committed for {@link #STALLED}. Standby is UP.
 * Spring Boot's liveness and readiness groups do not include it, so a broker problem never
 * restarts the application or stops it serving reads.
 */
@Component("streamConsumerHealthIndicator")
@ConditionalOnProperty(name = "wqs.stream.enabled", havingValue = "true")
public class StreamHealthIndicator implements HealthIndicator {

    static final Duration STALLED = Duration.ofMinutes(5);

    private final StreamConsumer consumer;

    public StreamHealthIndicator(StreamConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public Health health() {
        StreamConsumer.State state = consumer.state();
        Health.Builder health = Health.up().withDetail("state", state);
        if (state != StreamConsumer.State.CONSUMING) {
            return health.build();
        }
        health.withDetail("lastCommittedOffset", consumer.lastCommittedOffset());
        Instant last = consumer.lastCommitAt();
        if (last != null) {
            health.withDetail("lastCommitAt", last.toString());
        }
        var lag = consumer.lag();
        lag.ifPresent(l -> health.withDetail("lag", (long) l));
        boolean waiting = lag.isPresent() && lag.getAsDouble() > 0;
        boolean stale = last == null || Duration.between(last, Instant.now()).compareTo(STALLED) > 0;
        return waiting && stale ? health.down().withDetail("reason", "messages waiting, nothing committed for " + STALLED).build()
                : health.build();
    }
}
