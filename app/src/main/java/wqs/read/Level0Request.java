package wqs.read;

import java.time.Instant;
import java.util.Set;

/**
 * A level-0 page request over the half-open range {@code [from, to)}. Validation is the API's job
 * (Task 7): the reader assumes {@code page >= 1}, {@code pageSize >= 1} and {@code from <= to}.
 *
 * <p>The transaction-type filter is given either in {@code filter} or in {@code transactionTypes},
 * both as codes matched against the stored text directly (plan D2): an unknown code simply matches
 * nothing. Giving both is a programming error.
 */
public record Level0Request(
        Level0Filter filter, Set<String> transactionTypes, Instant from, Instant to, long page, long pageSize) {

    public Level0Request {
        transactionTypes = Set.copyOf(transactionTypes);
        if (!transactionTypes.isEmpty() && !filter.typeCodes().isEmpty()) {
            throw new IllegalArgumentException("transaction types are given in the filter or in the request, not both");
        }
    }

    public Level0Request(Level0Filter filter, Instant from, Instant to, long page, long pageSize) {
        this(filter, Set.of(), from, to, page, pageSize);
    }
}
