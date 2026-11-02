package wqs.gen;

/**
 * A small product pool for hand-built test items, as (productId, productType). Both are strings
 * since V10 and both fold as LEAST over a session's items, so the pool keeps the two orders
 * aligned: a lower id always has a lower type, and a session's product type -- the counter key --
 * moves exactly when its product id does.
 */
public final class Products {

    /** "P03", "P07", "P11", ... -- zero-padded so the string order is the numeric order. */
    public static String id(int n) {
        return "P%02d".formatted(n);
    }

    /** The type of product {@code n}: arcade (3) < baccarat (5) < bingo (7) < instants (11) < lottery (13). */
    public static String type(int n) {
        return switch (n) {
            case 3 -> "arcade";
            case 5 -> "baccarat";
            case 7 -> "bingo";
            case 11 -> "instants";
            case 12 -> "keno";
            case 13 -> "lottery";
            default -> throw new IllegalArgumentException("no test product " + n);
        };
    }

    private Products() {}
}
