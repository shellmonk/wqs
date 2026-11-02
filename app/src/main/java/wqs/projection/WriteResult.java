package wqs.projection;

/** Outcome of applying a batch. Duplicates are a signal, not noise (spec 11.2). */
public record WriteResult(int projected, int duplicates) {

    public int total() {
        return projected + duplicates;
    }
}
