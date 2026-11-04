package wqs.projection;

import java.time.Instant;

/**
 * What one transaction did to the session/play rollups.
 *
 * Rows derived from a session (its ENTRY count) are keyed on the SESSION's
 * (startedAt, product) -- never on the triggering transaction's -- and both can change: startedAt
 * moves earlier when an earlier transaction arrives (re-anchor), and productId / productType are the
 * first transaction's pair (plan D6), so they change when an earlier transaction -- or one at the same instant
 * with a lower (sourceId, source) -- arrives. The ENTRY count keys on the month of startedAt and on
 * productType.
 *
 * @param sessionIsNew               this transaction created the session
 * @param playIsNew                  this transaction created its play
 * @param sessionStart               the session's startedAt after this transaction (null only for NONE)
 * @param sessionProductId           the session's productId after this transaction (may be null)
 * @param sessionProductType         the session's productType after this transaction (may be null)
 * @param sessionFirstSource         the source code of the session's first transaction after this one
 *                                   (0 only for NONE): the session's entry is counted, and shown, under
 *                                   it (a session belongs to a read policy iff its first source does)
 * @param sessionCurrency            the session's currency (null only for NONE). A session is
 *                                   single-currency -- a mismatched transaction is dead-lettered before
 *                                   it rolls up -- so it never changes. Not part of any key;
 *                                   session page rows carry it.
 * @param previousSessionStart       the session's startedAt before this transaction; set only when
 *                                   {@code sessionKeyChanged}
 * @param previousSessionProductType the session's productType before this transaction; meaningful only
 *                                   when {@code sessionKeyChanged} (it may legitimately be null)
 * @param previousSessionFirstSource the session's first source before this transaction; meaningful
 *                                   only when {@code sessionKeyChanged}
 * @param sessionKeyChanged          an existing session's startedAt, productId, productType or first source
 *                                   changed, so rows keyed on or carrying the previous values
 *                                   must move or be updated
 * @param sessionRevived             an existing session had no surviving transaction (hidden with
 *                                   cancelled excluded) and this live transaction made it visible
 */
public record RollupResult(
        boolean sessionIsNew,
        boolean playIsNew,
        Instant sessionStart,
        String sessionProductId,
        String sessionProductType,
        short sessionFirstSource,
        String sessionCurrency,
        Instant previousSessionStart,
        String previousSessionProductType,
        short previousSessionFirstSource,
        boolean sessionKeyChanged,
        boolean sessionRevived) {

    /** The result for an unsessioned transaction or a cancellation: no rollup was touched. */
    public static final RollupResult NONE =
            new RollupResult(false, false, null, null, null, (short) 0, null, null, null, (short) 0, false, false);
}
