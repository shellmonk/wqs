package wqs.domain;

/** The counter keys that mean "all": shared by the projection (write) and read sides. */
public final class CounterSentinels {

    /** "productType" = '' is the ALL-product-types key; unreachable from input (blank is null). */
    public static final String ALL_PRODUCT_TYPES = "";

    /** "type" = '' is the ALL-transaction-types key; unreachable from input (a type code is never blank). */
    public static final String ALL_TYPES = "";

    private CounterSentinels() {}
}
