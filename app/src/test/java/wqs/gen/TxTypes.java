package wqs.gen;

/**
 * Seeded "wqTxType" ids (V10) for tests that build items by hand. {@code TxTypesTest} pins each
 * against the migrated table, so a reseeding that moves an id fails there, not as a silent
 * change of meaning in every test that uses it.
 */
public final class TxTypes {

    /** TT_BUY: direction PLAY. */
    public static final short PLAY = 1;
    /** PRIZE: direction WIN. */
    public static final short WIN = 8;
    /** TT_TRANSFER_OUT: direction NEUTRAL. */
    public static final short NEUTRAL = 13;
    /** TT_CANCEL: direction NEUTRAL; the type a cancellation carries. */
    public static final short CANCEL = 48;

    private TxTypes() {}
}
