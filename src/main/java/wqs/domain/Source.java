package wqs.domain;

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
}
