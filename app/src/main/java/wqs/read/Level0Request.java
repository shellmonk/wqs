package wqs.read;

import java.time.Instant;
import java.util.Set;

/**
 * A level-0 page request over the half-open range {@code [from, to)}. Validation is the API's job
 * (Task 7): the reader assumes {@code page >= 1}, {@code pageSize >= 1} and {@code from <= to}.
 *
 * <p>The transaction-type filter is given either as resolved ids in {@code filter} or as codes in
 * {@code transactionTypes}; the reader resolves codes inside its own transaction (an unknown code
 * matches nothing, and a non-empty list that resolves to nothing is an empty page without any
 * query). Giving both is a programming error.
 */
public record Level0Request(
        Level0Filter filter, Set<String> transactionTypes, Instant from, Instant to, long page, long pageSize) {

    public Level0Request {
        transactionTypes = Set.copyOf(transactionTypes);
        if (!transactionTypes.isEmpty() && !filter.typeIds().isEmpty()) {
            throw new IllegalArgumentException("transaction types are given as codes or as ids, not both");
        }
    }

    public Level0Request(Level0Filter filter, Instant from, Instant to, long page, long pageSize) {
        this(filter, Set.of(), from, to, page, pageSize);
    }
}
