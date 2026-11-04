package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.function.BiFunction;

/**
 * The optimisation itself (spec 7.6, plan D5), checked against an independent oracle count
 * rather than the reader's own segments. The walk's segments are the UTC months of [from, to) --
 * clamped to from and to at the partial ends -- with empty months merged, so a key query over
 * [lo, hi) with (offset, limit) must satisfy:
 * <ul>
 *   <li>hi is the end of the month holding the page's first unit: a month boundary inside the range,
 *       or to; the offset is a residual strictly inside that month [max(from, floorMonth(hi-)), hi);</li>
 *   <li>lo is the start of the month holding its last unit: a month boundary at or after from, or
 *       from; [lo, hi) holds offset + limit units and [the month after lo, hi) does not.</li>
 * </ul>
 * Naive OFFSET -- [from, to) with the full offset -- fails this whenever the page starts below
 * the newest month; the former {@code from} lower bound fails it whenever the page ends above the
 * oldest.
 */
final class WalkBounds {

    private WalkBounds() {}

    static void assertTight(RecordingLevel0Query.KeysCall call, Instant from, Instant to,
                            BiFunction<Instant, Instant, Long> count, String as) {
        Instant lo = call.lo();
        Instant hi = call.hi();
        assertThat(lo).as(as + " lo").isAfterOrEqualTo(from).isBefore(hi);
        assertThat(hi).as(as + " hi").isBeforeOrEqualTo(to);
        assertThat(lo.equals(from) || isMonthBoundary(lo)).as(as + " lo " + lo + " is from or a month start").isTrue();
        assertThat(hi.equals(to) || isMonthBoundary(hi)).as(as + " hi " + hi + " is to or a month end").isTrue();

        Instant targetStart = max(from, floorMonth(hi.minusNanos(1)));
        assertThat(call.offset()).as(as + " residual inside [" + targetStart + ", " + hi + ")")
                .isGreaterThanOrEqualTo(0).isLessThan(count.apply(targetStart, hi));

        long need = call.offset() + call.limit();
        assertThat(count.apply(lo, hi)).as(as + " page inside [lo, hi)").isGreaterThanOrEqualTo(need);
        Instant afterLo = min(hi, start(month(lo).plusMonths(1)));
        if (afterLo.isBefore(hi)) {
            assertThat(count.apply(afterLo, hi)).as(as + " lo is the oldest month the page needs").isLessThan(need);
        }
    }

    private static boolean isMonthBoundary(Instant i) {
        return floorMonth(i).equals(i);
    }

    private static YearMonth month(Instant i) {
        return YearMonth.from(i.atZone(ZoneOffset.UTC));
    }

    private static Instant start(YearMonth m) {
        return m.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant floorMonth(Instant i) {
        return start(month(i));
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
