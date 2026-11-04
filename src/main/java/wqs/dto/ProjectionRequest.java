package wqs.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import wqs.exception.InvalidParameterException;

/**
 * The validated parameters of a projection request. {@link #of} is the only way to build one, so a
 * read never sees an invalid request. {@code pageNumber} is 1-based. {@code from} is inclusive and
 * {@code to} exclusive; both are truncated to microseconds, the precision of timestamptz.
 */
public record ProjectionRequest(
        Instant from,
        Instant to,
        AggregationLevel level,
        boolean excludeCancelled,
        Set<String> productTypes,
        Set<String> transactionTypes,
        int pageNumber,
        int pageSize) {

    public static final int MAX_PAGE_SIZE = 1000;
    /** Bounds the size of the filter lists bound into one query. */
    public static final int MAX_LIST_VALUES = 50;

    public static ProjectionRequest of(Instant from, Instant to, Integer aggregationLevel, Boolean excludeCancelled,
                                       List<String> productTypes, List<String> transactionTypes,
                                       Integer pageNumber, Integer pageSize) {
        if (from == null) {
            throw new InvalidParameterException("from", "is required");
        }
        if (to == null) {
            throw new InvalidParameterException("to", "is required");
        }
        if (from.isAfter(to)) {
            throw new InvalidParameterException("from", "must not be after to");
        }
        AggregationLevel level;
        try {
            level = AggregationLevel.of(Objects.requireNonNullElse(aggregationLevel, 0));
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("aggregationLevel", "must be 0, 1 or 2");
        }
        int page = Objects.requireNonNullElse(pageNumber, 1);
        if (page < 1) {
            throw new InvalidParameterException("pageNumber", "must be at least 1");
        }
        int size = Objects.requireNonNullElse(pageSize, 50);
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidParameterException("pageSize", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        return new ProjectionRequest(from.truncatedTo(ChronoUnit.MICROS), to.truncatedTo(ChronoUnit.MICROS), level,
                Boolean.TRUE.equals(excludeCancelled), clean("productTypes", productTypes),
                clean("transactionTypes", transactionTypes), page, size);
    }

    /** Trims values and drops blanks; repeated and comma-separated forms arrive already split. */
    private static Set<String> clean(String name, List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        List<String> values = raw.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty()).toList();
        if (values.size() > MAX_LIST_VALUES) {
            throw new InvalidParameterException(name, "at most " + MAX_LIST_VALUES + " values");
        }
        return Set.copyOf(values);
    }

    public long offset() {
        return (long) (pageNumber - 1) * pageSize;
    }
}
