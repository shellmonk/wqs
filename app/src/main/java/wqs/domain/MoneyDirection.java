package wqs.domain;

/**
 * Whether a transaction type contributes to the wagered total, the won total, or neither.
 *
 * A type's direction is data, not code: it is stored per type in "wqTxType" (plan D2) and
 * resolved through the transaction-type registry. {@link #code()} is the stored value. An
 * unseen type is registered as NEUTRAL, so it is projected and visible rather than dead-lettered.
 */
public enum MoneyDirection {

    NEUTRAL((short) 0),
    PLAY((short) 1),
    WIN((short) 2);

    private final short code;

    MoneyDirection(short code) {
        this.code = code;
    }

    /** The value stored in "wqTxType"."direction". */
    public short code() {
        return code;
    }

    /** @throws IllegalArgumentException for a value no direction is stored as */
    public static MoneyDirection ofCode(short code) {
        for (MoneyDirection d : values()) {
            if (d.code == code) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown money direction code " + code);
    }
}
