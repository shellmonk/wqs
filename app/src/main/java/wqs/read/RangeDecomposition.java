package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import wqs.read.Segment.Tier;

/**
 * Splits [from, to) into whole UTC days and the live remainders at either end, newest first
 * (spec 7.5, plan D3). Whole days are read from the per-day counters, the partial boundary days by
 * a live count.
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
        List<Segment> out = new ArrayList<>();
        if (fromCeilDay.isAfter(toFloorDay)) {
            out.add(new Segment(from, to, Tier.LIVE));
            return out;
        }
        if (to.isAfter(toFloorDay)) {
            out.add(new Segment(toFloorDay, to, Tier.LIVE));
        }
        for (Instant d = toFloorDay; d.isAfter(fromCeilDay); ) {
            Instant prev = d.minusSeconds(DAY_SECONDS);
            out.add(new Segment(prev, d, Tier.DAY));
            d = prev;
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
