package wqs.dto;

/** The contract's {@code aggregationLevel}. */
public enum AggregationLevel {

    /** Flat transactions. */
    TRANSACTIONS,
    /** Sessions with their transactions, merged with transactions that have no session. */
    SESSIONS,
    /** Sessions with their plays and the plays' transactions, merged with transactions that have no session. */
    SESSIONS_AND_PLAYS;

    public static AggregationLevel of(int level) {
        return switch (level) {
            case 0 -> TRANSACTIONS;
            case 1 -> SESSIONS;
            case 2 -> SESSIONS_AND_PLAYS;
            default -> throw new IllegalArgumentException("aggregationLevel must be 0, 1 or 2");
        };
    }
}
