package wqs.read;

import java.util.Set;

/**
 * Level-0 read filter. An empty {@code productTypes} / {@code typeCodes} means "no filter"; a
 * non-empty one matches any of its members. The sets never hold a counter sentinel ({@code ''}
 * for ALL product types and for ALL transaction types): {@link CounterReader} sums the
 * cross product of the sets, so a sentinel next to real members would count projected transactions twice. The
 * constructor enforces it.
 *
 * @throws IllegalArgumentException if a product type or a transaction type code is blank
 */
public record Level0Filter(long userId, Set<String> productTypes, Set<String> typeCodes, boolean excludeCancelled) {

    public Level0Filter {
        productTypes = Set.copyOf(productTypes);
        typeCodes = Set.copyOf(typeCodes);
        for (String p : productTypes) {
            if (p.isBlank()) {
                throw new IllegalArgumentException("a product type filter must not be blank (sentinel for ALL)");
            }
        }
        for (String t : typeCodes) {
            if (t.isBlank()) {
                throw new IllegalArgumentException("a transaction type filter must not be blank (sentinel for ALL)");
            }
        }
    }

    /** No list filters. */
    public Level0Filter(long userId, boolean excludeCancelled) {
        this(userId, Set.of(), Set.of(), excludeCancelled);
    }

    Level0Filter withTypeCodes(Set<String> codes) {
        return new Level0Filter(userId, productTypes, codes, excludeCancelled);
    }
}
