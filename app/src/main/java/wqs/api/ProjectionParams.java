package wqs.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import wqs.domain.AggregationLevel;
import wqs.domain.DataDomain;

/**
 * The validated parameters shared by /web and /cc (spec 7.1). Construction through
 * {@link #validate} is the only way to get one, so a downstream read never sees an invalid
 * request: the reader assumes {@code page >= 1}, {@code pageSize >= 1} and {@code from <= to}.
 *
 * <p>{@code from} and {@code to} are normalised after parsing and before any read: truncated to
 * microseconds (Postgres' timestamptz precision, so Java's range decomposition and the SQL bounds
 * agree), then clamped into {@link DataDomain} (ruling C1: an expanded ISO year such as +1000000
 * would otherwise decompose into millions of month segments and reach Postgres out of range).
 *
 * <p>The phase-4 {@code productId} filter is gone (ruling R2): products are strings since V10
 * and the counters key on product type. The list filters ({@code productTypes}, {@code transactionTypes}) are bound here for now; Task 7 reshapes the API.
 *
 * <p>Every 400 rule is checked before {@link #requireLevel0}'s 501, so a malformed request is
 * reported as malformed whatever level it asks for.
 */
public record ProjectionParams(
        Instant from,
        Instant to,
        AggregationLevel level,
        boolean excludeCancelled,
        Set<String> productTypes,
        Set<String> transactionTypes,
        long page,
        long pageSize) {

    /** D10: at most this many values in one list filter (bounds the counter fan-in). */
    public static final int MAX_LIST_VALUES = 50;

    /**
     * @throws InvalidParameterException {@code aggregationLevel} outside 0..2; {@code from > to}
     *         (D6; {@code from == to} is a valid empty range); {@code page < 1};
     *         {@code pageSize < 1}. The {@code from > to}
     *         check sees the original values; bounds beyond the same domain edge clamp to an
     *         empty range ({@code from == to}).
     */
    public static ProjectionParams validate(Instant from, Instant to, int aggregationLevel,
            boolean excludeCancelled, long page, long pageSize) {
        return validate(from, to, aggregationLevel, excludeCancelled, null, null, page, pageSize);
    }

    /**
     * As above, with the list filters (D10). Each list is trimmed, blanks are dropped (blank is the
     * counters' ALL sentinel, never a filter value), and more than {@value #MAX_LIST_VALUES}
     * values in one list is a 400. Repeated and comma-separated forms arrive here already split.
     */
    public static ProjectionParams validate(Instant from, Instant to, int aggregationLevel,
            boolean excludeCancelled, List<String> productTypes, List<String> transactionTypes,
            long page, long pageSize) {
        AggregationLevel level;
        try {
            level = AggregationLevel.of(aggregationLevel);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("aggregationLevel", "must be 0, 1 or 2");
        }
        if (from.isAfter(to)) {
            throw new InvalidParameterException("from", "must not be after to");
        }
        if (page < 1) {
            throw new InvalidParameterException("page", "must be >= 1");
        }
        if (pageSize < 1) {
            throw new InvalidParameterException("pageSize", "must be >= 1");
        }
        return new ProjectionParams(normalise(from), normalise(to), level, excludeCancelled,
                cleanList("productTypes", productTypes), cleanList("transactionTypes", transactionTypes),
                page, pageSize);
    }

    private static Set<String> cleanList(String name, List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        List<String> values = raw.stream().filter(v -> v != null).map(String::trim).filter(v -> !v.isEmpty()).toList();
        if (values.size() > MAX_LIST_VALUES) {
            throw new InvalidParameterException(name, "at most " + MAX_LIST_VALUES + " values");
        }
        return Set.copyOf(values);
    }

    /** Truncates to microseconds (M3), then clamps into the data domain (C1). */
    private static Instant normalise(Instant bound) {
        Instant micros = bound.truncatedTo(ChronoUnit.MICROS);
        if (micros.isBefore(DataDomain.START)) {
            return DataDomain.START;
        }
        return micros.isAfter(DataDomain.END) ? DataDomain.END : micros;
    }

    /** /cc's operator-supplied user. @throws InvalidParameterException when {@code userId < 1} */
    public static long requireUserId(long userId) {
        if (userId < 1) {
            throw new InvalidParameterException("userId", "must be >= 1");
        }
        return userId;
    }

    /** @throws LevelNotImplementedException for levels 1 and 2 until Phase 5 (D4) */
    public ProjectionParams requireLevel0() {
        if (level != AggregationLevel.L0) {
            throw new LevelNotImplementedException(level);
        }
        return this;
    }
}
