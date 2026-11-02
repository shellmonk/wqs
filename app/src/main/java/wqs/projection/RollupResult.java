package wqs.projection;

import java.time.Instant;

/**
 * What one item did to the session/play rollups.
 *
 * {@code previousSessionStart} is non-null only when the item re-anchored its session to an
 * earlier startedAt; it is the old startedAt, which callers need to move rows keyed on it (the
 * page index, per-day counters).
 */
public record RollupResult(boolean sessionIsNew, boolean playIsNew, Instant previousSessionStart) {

    /** The result for an unsessioned item: no rollup was touched. */
    public static final RollupResult NONE = new RollupResult(false, false, null);

    public boolean sessionReanchored() {
        return previousSessionStart != null;
    }
}
