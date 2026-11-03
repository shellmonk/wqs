package wqs.gen;

/**
 * A small product pool for hand-built test transactions, as (productId, productType). Both are
 * strings. A session's pair is its FIRST transaction's (plan D6), never a per-column LEAST, so the pool
 * deliberately makes the two orders DISAGREE: P03 has the highest type ("lottery") and P13 the
 * lowest ("arcade"), so a per-column LEAST pairs P03 with "arcade" -- a product no transaction has.
 */
public final class Products {

    /** "P03", "P07", "P11", ... -- zero-padded so the string order is the numeric order. */
    public static String id(int n) {
        return "P%02d".formatted(n);
    }

    /** The type of product {@code n}. 11 is "instants", the default eInstant type: keep its transactions sessioned. */
    public static String type(int n) {
        return switch (n) {
            case 3 -> "lottery";
            case 5 -> "keno";
            case 7 -> "bingo";
            case 11 -> "instants";
            case 12 -> "baccarat";
            case 13 -> "arcade";
            default -> throw new IllegalArgumentException("no test product " + n);
        };
    }

    private Products() {}
}
