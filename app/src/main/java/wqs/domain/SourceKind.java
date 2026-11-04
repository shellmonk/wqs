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

    public static SourceKind ofCode(int code) {
        for (SourceKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown source code: " + code);
    }
}
