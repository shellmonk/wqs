package wqs.read;

import java.util.List;

/**
 * One page of a projection read -- the response body itself (spec 7.1). {@code pageSize} is the
 * effective size after any ceiling (spec 7.9); {@code totalPages == ceil(totalItems / pageSize)},
 * 0 when there are no transactions.
 */
public record ProjectionPage(
        long page, long pageSize, long totalItems, long totalPages, int aggregationLevel, List<TransactionEntry> transactions) {

    public ProjectionPage {
        transactions = List.copyOf(transactions);
    }
}
