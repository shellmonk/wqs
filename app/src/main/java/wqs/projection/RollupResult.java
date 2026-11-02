package wqs.projection;

import java.time.Instant;

/**
 * What one item did to the session/play rollups.
 *
 * Rows derived from a session (page index, high-level counters) are keyed on the SESSION's
 * (startedAt, productId) -- never on the triggering item's -- and both can change: startedAt
 * moves earlier when an earlier item arrives (re-anchor), and productId is LEAST over the
 * session's items, so it can go from null to a value or to a lower value.
 *
 * @param sessionIsNew             this item created the session
 * @param playIsNew                this item created its play
 * @param sessionStart             the session's startedAt after this item (null only for NONE)
 * @param sessionProductId         the session's productId after this item (may be null)
 * @param previousSessionStart     the session's startedAt before this item; set only when
 *                                 {@code sessionKeyChanged}
 * @param previousSessionProductId the session's productId before this item; meaningful only
 *                                 when {@code sessionKeyChanged} (it may legitimately be null)
 * @param sessionKeyChanged        an existing session's (startedAt, productId) changed, so rows
 *                                 keyed on the previous key must move to the current one
 */
public record RollupResult(
        boolean sessionIsNew,
        boolean playIsNew,
        Instant sessionStart,
        Integer sessionProductId,
        Instant previousSessionStart,
        Integer previousSessionProductId,
        boolean sessionKeyChanged) {

    /** The result for an unsessioned item or a cancellation: no rollup was touched. */
    public static final RollupResult NONE =
            new RollupResult(false, false, null, null, null, null, false);
}
