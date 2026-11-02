package wqs.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lives in {@code wqs.config}, not alongside the job beans it happens to serve, because
 * {@code wqs.config} must not depend on {@code wqs.jobs} -- jobs are entry points, never
 * collaborators (spec section 10).
 */
@Configuration
public class ClockConfig {

    /** UTC everywhere (spec 7.1). Injected rather than static so tests can fix time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
