package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import wqs.read.Segment.Tier;

/**
 * Splits [from, to) into at most three segments, newest first (spec 7.5, plan D3): the partial
 * day at the upper end (LIVE), ONE run of whole UTC days (DAY), and the partial day at the lower
 * end (LIVE). The whole days are read from the per-day counters, the partial days by a live count.
 * The output size never depends on the range's length, so any caller-supplied range is cheap to
 * decompose; {@link ProjectionReader} splits the DAY run by its active days.
 */
public final class RangeDecomposition {

    private static final long DAY_SECONDS = 86_400;

    private RangeDecomposition() {}

    public static List<Segment> newestFirst(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            return List.of();
        }
        Instant fromCeilDay = ceilDay(from);
        Instant toFloorDay = floorDay(to);
        List<Segment> out = new ArrayList<>(3);
        if (fromCeilDay.isAfter(toFloorDay)) {
            out.add(new Segment(from, to, Tier.LIVE));
            return out;
        }
        if (to.isAfter(toFloorDay)) {
            out.add(new Segment(toFloorDay, to, Tier.LIVE));
        }
        if (toFloorDay.isAfter(fromCeilDay)) {
            out.add(new Segment(fromCeilDay, toFloorDay, Tier.DAY));
        }
        if (from.isBefore(fromCeilDay)) {
            out.add(new Segment(from, fromCeilDay, Tier.LIVE));
        }
        return out;
    }

    private static Instant floorDay(Instant i) {
        return LocalDate.ofInstant(i, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant ceilDay(Instant i) {
        Instant f = floorDay(i);
        return f.equals(i) ? f : f.plusSeconds(DAY_SECONDS);
    }
}
