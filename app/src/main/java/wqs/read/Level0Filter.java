package wqs.read;

import java.util.Set;

/**
 * Level-0 read filter. An empty {@code productTypes} / {@code typeIds} means "no filter"; a
 * non-empty one matches any of its members. The sets never hold a counter sentinel ({@code ''}
 * for ALL product types, {@code 0} for ALL transaction types): {@link CounterReader} sums the
 * cross product of the sets, so a sentinel next to real members would count items twice. The
 * constructor enforces it.
 *
 * @throws IllegalArgumentException if a product type is blank or a type id is not positive
 */
public record Level0Filter(long userId, Set<String> productTypes, Set<Short> typeIds, boolean excludeCancelled) {

    public Level0Filter {
        productTypes = Set.copyOf(productTypes);
        typeIds = Set.copyOf(typeIds);
        for (String p : productTypes) {
            if (p.isBlank()) {
                throw new IllegalArgumentException("a product type filter must not be blank (sentinel for ALL)");
            }
        }
        for (Short t : typeIds) {
            if (t <= 0) {
                throw new IllegalArgumentException("a type id filter must be >= 1 (0 is the ALL sentinel): " + t);
            }
        }
    }

    /** No list filters. */
    public Level0Filter(long userId, boolean excludeCancelled) {
        this(userId, Set.of(), Set.of(), excludeCancelled);
    }

    Level0Filter withTypeIds(Set<Short> ids) {
        return new Level0Filter(userId, productTypes, ids, excludeCancelled);
    }
}
