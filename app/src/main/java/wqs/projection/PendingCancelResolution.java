package wqs.projection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves cancels whose target never arrived (plan D3): a cancel parked for longer than the
 * threshold is removed from "wqPrjPendingCancel", so it no longer waits for its target and no longer
 * holds the oldest-age gauge up. The cancel's own "wqPrjTransaction" row stays, an ordinary row; a
 * target arriving after this never sees the cancel.
 *
 * <p>Each removal is logged with ids, source and age only -- never the owner or an amount (no PII):
 * the first {@link #LOGGED_INDIVIDUALLY} of a run one WARN line each, the rest in one summary WARN line
 * with their count (final-review M8), so a producer fault parking thousands of orphan cancels cannot
 * flood the log. Run daily by the {@code pending-cancel-resolution} job; one transaction per run.
 */
@Service
public class PendingCancelResolution {

    private static final Logger log = LoggerFactory.getLogger(PendingCancelResolution.class);

    /** How many removals of one run are logged one line each; the rest are counted in one line. */
    static final int LOGGED_INDIVIDUALLY = 20;

    private final PendingCancelStore store;
    private final Clock clock;

    public PendingCancelResolution(PendingCancelStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Removes the cancels parked more than {@code threshold} ago; returns how many. */
    @Transactional
    public int resolveOlderThan(Duration threshold) {
        if (threshold.isNegative() || threshold.isZero()) {
            throw new IllegalArgumentException("threshold must be positive: " + threshold);
        }
        Instant now = clock.instant();
        List<PendingCancelStore.Expired> expired = store.expireParkedBefore(now.minus(threshold));
        for (PendingCancelStore.Expired cancel : expired.subList(0, Math.min(expired.size(), LOGGED_INDIVIDUALLY))) {
            log.warn("Resolved parked cancel {} (source {}, cancels {}) unapplied: its target did not arrive in {}"
                            + " (parked {} ago)",
                    cancel.cancelSourceId(), cancel.source(), cancel.targetSourceId(), threshold,
                    Duration.between(cancel.parkedAt(), now));
        }
        if (expired.size() > LOGGED_INDIVIDUALLY) {
            log.warn("Resolved {} more parked cancels unapplied (not logged one by one): {} in all older than {}",
                    expired.size() - LOGGED_INDIVIDUALLY, expired.size(), threshold);
        } else if (!expired.isEmpty()) {
            log.info("Resolved {} parked cancels older than {}", expired.size(), threshold);
        }
        return expired.size();
    }
}
