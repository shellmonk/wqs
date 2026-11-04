package wqs.read;

import java.time.Instant;

/**
 * A slice of a half-open UTC range (spec 7.5): DAY is whole UTC days -- one or more -- read from
 * the per-day counters, LIVE a partial day read by a live count.
 */
public record Segment(Instant start, Instant end, Tier tier) {

    public enum Tier { DAY, LIVE }
}
