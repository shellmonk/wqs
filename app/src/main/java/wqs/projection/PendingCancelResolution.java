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
 * <p>Each removal is logged with ids, source and age only -- never the owner or an amount (no PII).
 * Run daily by the {@code pending-cancel-resolution} job; one transaction per run.
 */
@Service
public class PendingCancelResolution {

    private static final Logger log = LoggerFactory.getLogger(PendingCancelResolution.class);

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
        for (PendingCancelStore.Expired cancel : expired) {
            log.warn("Resolved parked cancel {} (source {}, cancels {}) unapplied: its target did not arrive in {}"
                            + " (parked {} ago)",
                    cancel.cancelSourceId(), cancel.source(), cancel.targetSourceId(), threshold,
                    Duration.between(cancel.parkedAt(), now));
        }
        if (!expired.isEmpty()) {
            log.info("Resolved {} parked cancels older than {}", expired.size(), threshold);
        }
        return expired.size();
    }
}
