package wqs.projection;

/**
 * Outcome of applying a batch. Duplicates are a signal, not noise (spec 11.2).
 *
 * @param deadLettered transactions dead-lettered inside the batch instead of failing it: drained cancels
 *                     whose application failed (spec 6.3), and transactions an ingest rule rejects
 *                     ({@code currency-mismatch}, {@code einstant-unsessioned})
 * Every transaction is stored, zero-amount and non-player ones included, so nothing is skipped:
 * a zero-amount transaction counts as projected.
 */
public record WriteResult(int projected, int duplicates, int deadLettered) {

    public WriteResult(int projected, int duplicates) {
        this(projected, duplicates, 0);
    }

    public int total() {
        return projected + duplicates;
    }
}
