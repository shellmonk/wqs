package wqs.projection;

/**
 * Outcome of one {@link DeltaBatch}, one count per {@code wqs.delta.rows{outcome}} value (plan D10).
 *
 * @param inserted     first sight of the row: projected like a stream transaction
 * @param updated      a strictly newer version: "detail" and the stored version replaced
 * @param duplicates   the stored version again (the overlap window)
 * @param stale        an older version than the stored one: ignored
 * @param skipped      a non-player owner: nothing written (R1)
 * @param deadLettered an ingest-rule rejection on first sight, or a newer version that changes an
 *                     immutable field ({@code immutable-field-changed}, D6)
 */
public record DeltaResult(int inserted, int updated, int duplicates, int stale, int skipped, int deadLettered) {

    public int total() {
        return inserted + updated + duplicates + stale + skipped + deadLettered;
    }
}
