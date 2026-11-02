package wqs.projection;

/**
 * Outcome of applying a batch. Duplicates are a signal, not noise (spec 11.2).
 *
 * @param deadLettered drained cancels whose application failed and that were dead-lettered
 *                     instead of failing the batch (spec 6.3)
 */
public record WriteResult(int projected, int duplicates, int deadLettered) {

    public WriteResult(int projected, int duplicates) {
        this(projected, duplicates, 0);
    }

    public int total() {
        return projected + duplicates;
    }
}
