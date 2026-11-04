package wqs.dto;

import java.util.List;

/**
 * The response body of every projection endpoint. {@code pageNumber} is 1-based; {@code pagesAvailable}
 * is {@code ceil(totalRowCount / pageSize)}, 0 when there are no rows. At levels 1 and 2 a row is a
 * top-level entry: a session or a transaction without a session.
 */
public record ProjectionResponse<T>(int pageNumber, long pagesAvailable, int pageSize, long totalRowCount, List<T> data) {

    public ProjectionResponse {
        data = List.copyOf(data);
    }

    public static <T> ProjectionResponse<T> of(ProjectionRequest request, long totalRowCount, List<T> data) {
        long pages = totalRowCount == 0 ? 0 : Math.ceilDiv(totalRowCount, request.pageSize());
        return new ProjectionResponse<>(request.pageNumber(), pages, request.pageSize(), totalRowCount, data);
    }
}
