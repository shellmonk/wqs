package wqs.domain;

/** The counter keys that mean "all": shared by the projection (write) and read sides. */
public final class CounterSentinels {

    /** "productType" = '' is the ALL-product-types key; unreachable from input (blank is null). */
    public static final String ALL_PRODUCT_TYPES = "";

    /** "typeId" = 0 is the ALL-transaction-types key; real "wqTxType" ids start at 1. */
    public static final short ALL_TYPES = 0;

    private CounterSentinels() {}
}
