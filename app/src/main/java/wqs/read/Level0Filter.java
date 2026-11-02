package wqs.read;

/**
 * Level-0 read filter. Product and transaction-type list filters arrive with the phase-5
 * contract (plan Task 5); until then a read covers every product type and type (ruling R2).
 */
public record Level0Filter(long userId, boolean excludeCancelled) {

    /** The counter key holding the ALL-product-types total ("productType" = '', V10). */
    public static final String ALL_PRODUCT_TYPES = "";

    /** The counter key holding the ALL-transaction-types total ("typeId" = 0, V10). */
    public static final short ALL_TYPES = 0;
}
