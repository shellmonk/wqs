package wqs.read;

import java.util.Set;

/**
 * Level-1/2 read filter over the entries (plan D4). An empty {@code productTypes} means "no
 * filter"; a non-empty one matches any of its members. {@code excludeCancelled} keeps only rows
 * with "visibleExclCancelled". There is no transaction-type filter: transaction types never change
 * level-1/2 page counts (spec 4.1 (b), plan D4); they filter the nested transactions only.
 *
 * <p>Like {@link Level0Filter}, the set never holds the ALL-product-types sentinel {@code ''}:
 * {@link CounterReader} sums over the set's members, so a sentinel next to real members would
 * count rows twice. The constructor enforces it.
 *
 * @throws IllegalArgumentException if a product type is blank
 */
public record HighLevelFilter(long userId, Set<String> productTypes, boolean excludeCancelled) {

    public HighLevelFilter {
        productTypes = Set.copyOf(productTypes);
        for (String p : productTypes) {
            if (p.isBlank()) {
                throw new IllegalArgumentException("a product type filter must not be blank (sentinel for ALL)");
            }
        }
    }

    /** No list filter. */
    public HighLevelFilter(long userId, boolean excludeCancelled) {
        this(userId, Set.of(), excludeCancelled);
    }
}
