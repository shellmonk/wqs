package wqs.read;

import java.time.Instant;

/**
 * A level-0 page request over the half-open range {@code [from, to)}. Validation is the API's job
 * (Task 7): the reader assumes {@code page >= 1}, {@code pageSize >= 1} and {@code from <= to}.
 */
public record Level0Request(Level0Filter filter, Instant from, Instant to, long page, long pageSize) {}
