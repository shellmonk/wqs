package wqs.read;

import java.time.Instant;
import java.util.Objects;
import wqs.domain.AggregationLevel;

/**
 * A level-1/2 page request over the half-open range {@code [from, to)} of one view's entries.
 * Levels 1 and 2 page the same unit and differ only in assembly (spec 5.7), so {@code level} is
 * carried for the response and the metrics' aggregationLevel tag, never for the walk. Validation of
 * the numbers is the API's job (Task 7): the reader assumes {@code page >= 1},
 * {@code pageSize >= 1} and {@code from <= to}.
 *
 * @throws IllegalArgumentException if {@code level} is {@link AggregationLevel#L0}
 */
public record HighLevelRequest(
        ViewKind view, AggregationLevel level, HighLevelFilter filter,
        Instant from, Instant to, long page, long pageSize) {

    public HighLevelRequest {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(filter, "filter");
        if (level == null || level == AggregationLevel.L0) {
            throw new IllegalArgumentException("a high-level request is level 1 or 2, was " + level);
        }
    }
}
