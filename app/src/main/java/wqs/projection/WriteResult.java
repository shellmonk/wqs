package wqs.projection;

/**
 * Outcome of applying a batch. Duplicates are a signal, not noise (spec 11.2).
 *
 * @param deadLettered transactions dead-lettered inside the batch instead of failing it: drained cancels
 *                     whose application failed (spec 6.3), and transactions an ingest rule rejects
 *                     ({@code currency-mismatch}, {@code einstant-unsessioned})
 * @param skipped      zero-amount transactions newly recorded in the locator only (spec 4.1, D8)
 */
public record WriteResult(int projected, int duplicates, int deadLettered, int skipped) {

    public WriteResult(int projected, int duplicates, int deadLettered) {
        this(projected, duplicates, deadLettered, 0);
    }

    public WriteResult(int projected, int duplicates) {
        this(projected, duplicates, 0, 0);
    }

    public int total() {
        return projected + duplicates;
    }
}
