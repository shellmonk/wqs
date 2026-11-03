package wqs.gen;

import wqs.domain.TransactionType;

/**
 * Transaction-type codes for tests that build transactions by hand, one per direction.
 * {@code TxTypesTest} pins each against {@link TransactionType}, so an enum change that moves a
 * direction fails there, not as a silent change of meaning in every test that uses it.
 */
public final class TxTypes {

    /** TT_BUY: direction PLAY. */
    public static final String PLAY = "TT_BUY";
    /** PRIZE: direction WIN. */
    public static final String WIN = "PRIZE";
    /** TT_TRANSFER_OUT: direction NEUTRAL. */
    public static final String NEUTRAL = "TT_TRANSFER_OUT";
    /** TT_CANCEL: direction NEUTRAL; the type a cancellation carries. */
    public static final String CANCEL = "TT_CANCEL";

    private TxTypes() {}
}
