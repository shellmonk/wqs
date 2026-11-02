package wqs.projection;

import java.time.Instant;

/**
 * What one item did to the session/play rollups.
 *
 * Rows derived from a session (page index, high-level counters) are keyed on the SESSION's
 * (startedAt, product) -- never on the triggering item's -- and both can change: startedAt
 * moves earlier when an earlier item arrives (re-anchor), and productId / productType are the
 * first item's pair (plan D6), so they change when an earlier item -- or one at the same instant
 * with a lower (sourceId, source) -- arrives. The counters key on productType; the page index
 * stores both.
 *
 * @param sessionIsNew               this item created the session
 * @param playIsNew                  this item created its play
 * @param sessionStart               the session's startedAt after this item (null only for NONE)
 * @param sessionProductId           the session's productId after this item (may be null)
 * @param sessionProductType         the session's productType after this item (may be null)
 * @param sessionCurrency            the session's currency (null only for NONE). A session is
 *                                   single-currency -- a mismatched item is dead-lettered before
 *                                   it rolls up -- so it never changes. Not part of any key;
 *                                   session page rows carry it.
 * @param previousSessionStart       the session's startedAt before this item; set only when
 *                                   {@code sessionKeyChanged}
 * @param previousSessionProductType the session's productType before this item; meaningful only
 *                                   when {@code sessionKeyChanged} (it may legitimately be null)
 * @param sessionKeyChanged          an existing session's startedAt, productId or productType
 *                                   changed, so rows keyed on or carrying the previous values
 *                                   must move or be updated
 */
public record RollupResult(
        boolean sessionIsNew,
        boolean playIsNew,
        Instant sessionStart,
        String sessionProductId,
        String sessionProductType,
        String sessionCurrency,
        Instant previousSessionStart,
        String previousSessionProductType,
        boolean sessionKeyChanged) {

    /** The result for an unsessioned item or a cancellation: no rollup was touched. */
    public static final RollupResult NONE =
            new RollupResult(false, false, null, null, null, null, null, null, false);
}
