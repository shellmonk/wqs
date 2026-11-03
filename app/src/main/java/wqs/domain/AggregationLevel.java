package wqs.domain;

/** The {@code aggregationLevel} request parameter (spec 7.1): 0 transactions, 1 sessions, 2 plays. */
public enum AggregationLevel {
    L0, L1, L2;

    public int value() {
        return ordinal();
    }

    /** @throws IllegalArgumentException outside 0..2 */
    public static AggregationLevel of(int level) {
        AggregationLevel[] all = values();
        if (level < 0 || level >= all.length) {
            throw new IllegalArgumentException("aggregationLevel must be 0, 1 or 2, was " + level);
        }
        return all[level];
    }
}
