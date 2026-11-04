package wqs.read;

import java.time.Instant;

/**
 * A slice of a half-open UTC range (spec 7.5, plan D5): MONTH is whole UTC months -- one or more --
 * read from the owner-month counts, LIVE a partial month read by a live count.
 */
public record Segment(Instant start, Instant end, Tier tier) {

    public enum Tier { MONTH, LIVE }
}
