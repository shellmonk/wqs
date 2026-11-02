package wqs.read;

import java.time.Instant;

/** A slice of a half-open UTC range, read from the monthly counters, daily counters or the live table (spec 7.5). */
public record Segment(Instant start, Instant end, Tier tier) {

    public enum Tier { MONTH, DAY, LIVE }
}
