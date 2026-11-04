package wqs.read;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import wqs.read.Segment.Tier;

/**
 * Splits [from, to) into at most three segments, newest first (spec 7.5, plan D5): the partial
 * month at the upper end (LIVE), ONE run of whole UTC months (MONTH), and the partial month at the
 * lower end (LIVE). The whole months are read from the owner-month counts, the partial months by a
 * live count. The output size never depends on the range's length, so any caller-supplied range is
 * cheap to decompose; {@link ProjectionReader} splits the MONTH run by its active months.
 */
public final class RangeDecomposition {

    private RangeDecomposition() {}

    public static List<Segment> newestFirst(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            return List.of();
        }
        Instant fromCeil = ceilMonth(from);
        Instant toFloor = floorMonth(to);
        List<Segment> out = new ArrayList<>(3);
        if (fromCeil.isAfter(toFloor)) {
            out.add(new Segment(from, to, Tier.LIVE));
            return out;
        }
        if (to.isAfter(toFloor)) {
            out.add(new Segment(toFloor, to, Tier.LIVE));
        }
        if (toFloor.isAfter(fromCeil)) {
            out.add(new Segment(fromCeil, toFloor, Tier.MONTH));
        }
        if (from.isBefore(fromCeil)) {
            out.add(new Segment(from, fromCeil, Tier.LIVE));
        }
        return out;
    }

    /** The UTC month holding {@code i}. */
    static YearMonth monthOf(Instant i) {
        return YearMonth.from(i.atZone(ZoneOffset.UTC));
    }

    static Instant start(YearMonth m) {
        return m.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant floorMonth(Instant i) {
        return start(monthOf(i));
    }

    private static Instant ceilMonth(Instant i) {
        Instant f = floorMonth(i);
        return f.equals(i) ? f : start(monthOf(i).plusMonths(1));
    }
}
