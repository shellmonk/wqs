package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import wqs.read.Segment.Tier;

/** Splits [from, to) into maximal whole months, whole days and live remainders, newest first (spec 7.5). */
public final class RangeDecomposition {

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
        Instant firstMonth = ceilMonth(fromCeilDay);
        Instant lastMonth = floorMonth(toFloorDay);
        if (firstMonth.isAfter(lastMonth)) {
            days(out, fromCeilDay, toFloorDay);
        } else {
            days(out, lastMonth, toFloorDay);
            for (Instant m = lastMonth; m.isAfter(firstMonth); ) {
                Instant prev = plusMonths(m, -1);
                out.add(new Segment(prev, m, Tier.MONTH));
                m = prev;
            }
            days(out, fromCeilDay, firstMonth);
        }
        if (from.isBefore(fromCeilDay)) {
            out.add(new Segment(from, fromCeilDay, Tier.LIVE));
        }
        return out;
    }

    /** Appends the days of [start, end) newest first. */
    private static void days(List<Segment> out, Instant start, Instant end) {
        for (Instant d = end; d.isAfter(start); ) {
            Instant prev = d.minusSeconds(86_400);
            out.add(new Segment(prev, d, Tier.DAY));
            d = prev;
        }
    }

    private static Instant floorDay(Instant i) {
        return date(i).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant ceilDay(Instant i) {
        Instant f = floorDay(i);
        return f.equals(i) ? f : f.plusSeconds(86_400);
    }

    private static Instant floorMonth(Instant i) {
        return date(i).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant ceilMonth(Instant i) {
        Instant f = floorMonth(i);
        return f.equals(i) ? f : plusMonths(f, 1);
    }

    private static Instant plusMonths(Instant monthStart, int n) {
        return date(monthStart).plusMonths(n).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate date(Instant i) {
        return LocalDate.ofInstant(i, ZoneOffset.UTC);
    }
}
