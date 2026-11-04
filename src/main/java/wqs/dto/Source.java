package wqs.dto;

/** Where a projected row came from, by its stored code. */
public enum Source {

    /** {@code walAccountTransactions}, over the RabbitMQ stream. */
    TRANSACTION(1),
    MANUAL_ADJUSTMENT(2),
    CLAIMABLE_PRIZE(3),
    PAYMENT(4),
    LIMIT(5);

    private final short code;

    Source(int code) {
        this.code = (short) code;
    }

    public short code() {
        return code;
    }

    /**
     * Only game rounds carry a session batch id: transactions and claimable prizes. A batched row
     * of any other source is a producer fault and is rejected, so a session never mixes in
     * payments, adjustments or limits.
     */
    public boolean carriesBatch() {
        return this == TRANSACTION || this == CLAIMABLE_PRIZE;
    }
}
