package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.BiFunction;

/**
 * The optimisation itself (spec 7.6, plans D3/D4), checked against an independent oracle count
 * rather than the reader's own segments. The walk's segments are the UTC days of [from, to) --
 * clamped to from and to at the partial ends -- with empty days merged, so a key query over
 * [lo, hi) with (offset, limit) must satisfy:
 * <ul>
 *   <li>hi is the end of the day holding the page's first unit: a day boundary inside the range,
 *       or to; the offset is a residual strictly inside that day [max(from, floorDay(hi-)), hi);</li>
 *   <li>lo is the start of the day holding its last unit: a day boundary at or after from, or
 *       from; [lo, hi) holds offset + limit units and [the day after lo, hi) does not.</li>
 * </ul>
 * Naive OFFSET -- [from, to) with the full offset -- fails this whenever the page starts below
 * the newest day; the former {@code from} lower bound fails it whenever the page ends above the
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
        assertThat(lo.equals(from) || isDayBoundary(lo)).as(as + " lo " + lo + " is from or a day start").isTrue();
        assertThat(hi.equals(to) || isDayBoundary(hi)).as(as + " hi " + hi + " is to or a day end").isTrue();

        Instant targetStart = max(from, floorDay(hi.minusNanos(1)));
        assertThat(call.offset()).as(as + " residual inside [" + targetStart + ", " + hi + ")")
                .isGreaterThanOrEqualTo(0).isLessThan(count.apply(targetStart, hi));

        long need = call.offset() + call.limit();
        assertThat(count.apply(lo, hi)).as(as + " page inside [lo, hi)").isGreaterThanOrEqualTo(need);
        Instant afterLo = min(hi, floorDay(lo).plusSeconds(86_400));
        if (afterLo.isBefore(hi)) {
            assertThat(count.apply(afterLo, hi)).as(as + " lo is the oldest day the page needs").isLessThan(need);
        }
    }

    private static boolean isDayBoundary(Instant i) {
        return floorDay(i).equals(i);
    }

    private static Instant floorDay(Instant i) {
        return LocalDate.ofInstant(i, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
