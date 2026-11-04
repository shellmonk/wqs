package wqs.domain;

/**
 * The five ingestion sources (spec section 4), by their stored code. Which endpoint shows which
 * source is not domain knowledge: each endpoint's {@code wqs.read.ReadPolicy} states it.
 */
public enum SourceKind {

    TRANSACTION(1),
    MANUAL_ADJUSTMENT(2),
    CLAIMABLE_PRIZE(3),
    PAYMENT(4),
    LIMIT(5);

    private final int code;

    SourceKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * Whether the producer emits this source inside a session (a batch id): only game rounds --
     * transactions and claimable prizes -- are batched. Payments, manual adjustments and limits never
     * are, so a batched row of one of them is a producer fault the writer dead-letters
     * ({@code sessioned-source-not-on-every-view}). A fact about the producer, not about an endpoint:
     * it is what keeps every member of a session of one kind, so a session's stored totals are those
     * of the transactions any endpoint showing the session shows.
     */
    public boolean carriesBatch() {
        return this == TRANSACTION || this == CLAIMABLE_PRIZE;
    }

    public static SourceKind ofCode(int code) {
        for (SourceKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown source code: " + code);
    }
}
