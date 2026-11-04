package wqs.domain;

/**
 * The owner-month count keys that mean "none" (plan D5): shared by the projection (write) and read
 * sides. Counts are stored per EXACT combination, so there is no "all" key.
 */
public final class CounterSentinels {

    /** "productType" = '' is a row with no product type; unreachable from input (blank is null). */
    public static final String NO_PRODUCT_TYPE = "";

    /** "type" = '' on every ENTRY row (levels 1-2 filter no type); a type code is never blank. */
    public static final String NO_TYPE = "";

    private CounterSentinels() {}
}
