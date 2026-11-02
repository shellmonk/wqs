package wqs.api;

import java.time.Instant;
import wqs.domain.AggregationLevel;

/**
 * The validated parameters shared by /web and /cc (spec 7.1). Construction through
 * {@link #validate} is the only way to get one, so a downstream read never sees an invalid
 * request: the reader assumes {@code page >= 1}, {@code pageSize >= 1} and {@code from <= to}.
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

    /**
     * @throws InvalidParameterException {@code aggregationLevel} outside 0..2; {@code from > to}
     *         (D6; {@code from == to} is a valid empty range); {@code productId <= 0} (D5: 0 is
     *         the counter sentinel); {@code page < 1}; {@code pageSize < 1}
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
        return new ProjectionParams(from, to, level, productId, excludeCancelled, page, pageSize);
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
