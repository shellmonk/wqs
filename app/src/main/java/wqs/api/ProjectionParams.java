package wqs.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import wqs.domain.AggregationLevel;

/**
 * The validated parameters shared by /web and /cc (spec 7.1). Construction through
 * {@link #validate} is the only way to get one, so a downstream read never sees an invalid
 * request: the reader assumes {@code page >= 1}, {@code pageSize >= 1} and {@code from <= to}.
 *
 * <p>{@code from} and {@code to} are normalised after parsing and before any read: truncated to
 * microseconds (Postgres' timestamptz precision, so Java's range decomposition and the SQL bounds
 * agree), then clamped into the data domain {@code [DOMAIN_START, DOMAIN_END)}.
 *
 * <p>Every 400 rule is checked before {@link #requireLevel0}'s 501, so a malformed request is
 * reported as malformed whatever level it asks for.
 */
public record ProjectionParams(
        Instant from,
        Instant to,
        AggregationLevel level,
        Integer productId,
        boolean excludeCancelled,
        long page,
        long pageSize) {

    /*
     * The data domain [DOMAIN_START, DOMAIN_END) every range is clamped into (final-review ruling
     * C1). The clamp is exact: the item tables have no default partition, so no row can exist
     * outside provisioned months, all of which lie inside the domain. It bounds any range
     * decomposition to ~2,760 months -- an expanded ISO year such as +1000000 would otherwise
     * build one segment per month (millions) -- and keeps every bound inside Postgres'
     * timestamptz range.
     */
    public static final Instant DOMAIN_START = Instant.parse("1970-01-01T00:00:00Z");
    public static final Instant DOMAIN_END = Instant.parse("2200-01-01T00:00:00Z");

    /**
     * @throws InvalidParameterException {@code aggregationLevel} outside 0..2; {@code from > to}
     *         (D6; {@code from == to} is a valid empty range); {@code productId <= 0} (D5: 0 is
     *         the counter sentinel); {@code page < 1}; {@code pageSize < 1}. The {@code from > to}
     *         check sees the original values; bounds beyond the same domain edge clamp to an
     *         empty range ({@code from == to}).
     */
    public static ProjectionParams validate(Instant from, Instant to, int aggregationLevel,
            Integer productId, boolean excludeCancelled, long page, long pageSize) {
        AggregationLevel level;
        try {
            level = AggregationLevel.of(aggregationLevel);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("aggregationLevel", "must be 0, 1 or 2");
        }
        if (from.isAfter(to)) {
            throw new InvalidParameterException("from", "must not be after to");
        }
        if (productId != null && productId < 1) {
            throw new InvalidParameterException("productId", "must be >= 1 when given");
        }
        if (page < 1) {
            throw new InvalidParameterException("page", "must be >= 1");
        }
        if (pageSize < 1) {
            throw new InvalidParameterException("pageSize", "must be >= 1");
        }
        return new ProjectionParams(normalise(from), normalise(to), level, productId, excludeCancelled, page, pageSize);
    }

    /** Truncates to microseconds (M3), then clamps into the data domain (C1). */
    private static Instant normalise(Instant bound) {
        Instant micros = bound.truncatedTo(ChronoUnit.MICROS);
        if (micros.isBefore(DOMAIN_START)) {
            return DOMAIN_START;
        }
        return micros.isAfter(DOMAIN_END) ? DOMAIN_END : micros;
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
