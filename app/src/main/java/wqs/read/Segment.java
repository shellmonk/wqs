package wqs.read;

import java.time.Instant;

/** A slice of a half-open UTC range, read from the per-day counters or the live table (spec 7.5). */
public record Segment(Instant start, Instant end, Tier tier) {

    public enum Tier { DAY, LIVE }
}
