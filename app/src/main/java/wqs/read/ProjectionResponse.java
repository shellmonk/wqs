package wqs.read;

import java.util.List;

/**
 * The response body of every projection endpoint (api-contract). {@code pageNumber} is 1-based;
 * {@code pageSize} is the effective size after any ceiling (spec 7.9); {@code pagesAvailable} is
 * {@code ceil(totalRowCount / pageSize)}, 0 when there are no rows.
 */
public record ProjectionResponse<T>(int pageNumber, long pagesAvailable, int pageSize, long totalRowCount, List<T> data) {

    public ProjectionResponse {
        data = List.copyOf(data);
    }

    /** The level-0 response for a reader page. */
    public static ProjectionResponse<TxView> ofLevel0(ProjectionPage page) {
        return new ProjectionResponse<>((int) page.page(), page.totalPages(), (int) page.pageSize(),
                page.totalItems(), page.items().stream().map(TxView::of).toList());
    }

    /** The level-1/2 response for a high-level page served at {@code pageSize} (the effective size). */
    public static ProjectionResponse<HighLevelEntry> ofHighLevel(HighLevelPage<HighLevelEntry> page, long pageNumber,
                                                                 long pageSize) {
        long total = page.totalRowCount();
        return new ProjectionResponse<>((int) pageNumber, total == 0 ? 0 : Math.ceilDiv(total, pageSize),
                (int) pageSize, total, page.entries());
    }
}
