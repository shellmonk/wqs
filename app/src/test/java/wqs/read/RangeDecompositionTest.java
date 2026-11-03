package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.junit.jupiter.api.Test;
import wqs.read.Segment.Tier;

@Tag("property")
class RangeDecompositionTest {

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    private static Segment seg(String from, String to, Tier tier) {
        return new Segment(t(from), t(to), tier);
    }

    /** Plan D3: the full clamped domain [1970, 2200) decomposes into its whole days, no live end. */
    @Test
    void full_clamped_domain_is_whole_days() {
        List<Segment> segments = RangeDecomposition.newestFirst(
                wqs.domain.DataDomain.START, wqs.domain.DataDomain.END);
        assertThat(segments).allMatch(s -> s.tier() == Tier.DAY);
        assertThat(segments).hasSize((int) ChronoUnit.DAYS.between(
                wqs.domain.DataDomain.START, wqs.domain.DataDomain.END));
    }

    @Test
    void same_day_is_one_live_segment() {
        assertThat(RangeDecomposition.newestFirst(t("2034-03-05T10:00:00Z"), t("2034-03-05T18:00:00Z")))
                .containsExactly(seg("2034-03-05T10:00:00Z", "2034-03-05T18:00:00Z", Tier.LIVE));
    }

    @Test
    void partial_days_are_live_and_whole_days_are_days() {
        assertThat(RangeDecomposition.newestFirst(t("2034-03-05T10:00:00Z"), t("2034-03-09T06:00:00Z")))
                .containsExactly(
                        seg("2034-03-09T00:00:00Z", "2034-03-09T06:00:00Z", Tier.LIVE),
                        seg("2034-03-08T00:00:00Z", "2034-03-09T00:00:00Z", Tier.DAY),
                        seg("2034-03-07T00:00:00Z", "2034-03-08T00:00:00Z", Tier.DAY),
                        seg("2034-03-06T00:00:00Z", "2034-03-07T00:00:00Z", Tier.DAY),
                        seg("2034-03-05T10:00:00Z", "2034-03-06T00:00:00Z", Tier.LIVE));
    }

    @Test
    void exact_boundaries_produce_no_live_segment() {
        assertThat(RangeDecomposition.newestFirst(t("2034-01-01T00:00:00Z"), t("2034-01-04T00:00:00Z")))
                .containsExactly(
                        seg("2034-01-03T00:00:00Z", "2034-01-04T00:00:00Z", Tier.DAY),
                        seg("2034-01-02T00:00:00Z", "2034-01-03T00:00:00Z", Tier.DAY),
                        seg("2034-01-01T00:00:00Z", "2034-01-02T00:00:00Z", Tier.DAY));
    }

    @Test
    void to_on_a_day_boundary_has_no_trailing_live() {
        assertThat(RangeDecomposition.newestFirst(t("2034-01-30T12:00:00Z"), t("2034-02-02T00:00:00Z")))
                .containsExactly(
                        seg("2034-02-01T00:00:00Z", "2034-02-02T00:00:00Z", Tier.DAY),
                        seg("2034-01-31T00:00:00Z", "2034-02-01T00:00:00Z", Tier.DAY),
                        seg("2034-01-30T12:00:00Z", "2034-01-31T00:00:00Z", Tier.LIVE));
    }

    @Test
    void five_year_range_counts() {
        List<Segment> s = RangeDecomposition.newestFirst(t("2030-01-15T13:00:00Z"), t("2035-01-15T09:00:00Z"));
        // 2030-01-16 .. 2035-01-14 inclusive: 1,827 days spanning 5 years, two of them leap (2032).
        assertThat(s.stream().filter(x -> x.tier() == Tier.DAY)).hasSize(
                (int) ChronoUnit.DAYS.between(LocalDate.parse("2030-01-16"), LocalDate.parse("2035-01-15")));
        assertThat(s.stream().filter(x -> x.tier() == Tier.LIVE)).hasSize(2);
    }

    @Test
    void empty_and_inverted_ranges_are_empty() {
        Instant a = t("2034-01-01T00:00:00Z");
        assertThat(RangeDecomposition.newestFirst(a, a)).isEmpty();
        assertThat(RangeDecomposition.newestFirst(a.plusSeconds(1), a)).isEmpty();
    }

    private static final Instant LO = t("2029-12-20T00:00:00Z");
    private static final Instant HI = t("2031-02-10T00:00:00Z");

    @Provide
    Arbitrary<Instant> instants() {
        long span = HI.getEpochSecond() - LO.getEpochSecond();
        return Arbitraries.longs().between(0, span).flatMap(off -> Arbitraries.integers().between(0, 3).map(mode -> {
            Instant i = LO.plusSeconds(off);
            return switch (mode) {
                case 0 -> i.truncatedTo(ChronoUnit.DAYS);
                case 1 -> LocalDate.ofInstant(i, ZoneOffset.UTC).withDayOfMonth(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
                default -> i;
            };
        }));
    }

    @Property
    void segments_tile_the_range_newest_first(@ForAll("instants") Instant a, @ForAll("instants") Instant b) {
        List<Segment> segs = RangeDecomposition.newestFirst(a, b);
        if (!a.isBefore(b)) {
            assertThat(segs).isEmpty();
            return;
        }
        assertThat(segs).isNotEmpty();
        assertThat(segs.get(0).end()).isEqualTo(b);
        assertThat(segs.get(segs.size() - 1).start()).isEqualTo(a);
        for (int i = 1; i < segs.size(); i++) {
            assertThat(segs.get(i).end()).isEqualTo(segs.get(i - 1).start());
        }
        long live = 0;
        for (Segment s : segs) {
            assertThat(s.start()).isBefore(s.end());
            LocalDate d = LocalDate.ofInstant(s.start(), ZoneOffset.UTC);
            switch (s.tier()) {
                case DAY -> {
                    assertThat(s.start()).isEqualTo(d.atStartOfDay(ZoneOffset.UTC).toInstant());
                    assertThat(s.end()).isEqualTo(d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                }
                case LIVE -> {
                    live++;
                    assertThat(LocalDate.ofInstant(s.start(), ZoneOffset.UTC))
                            .isEqualTo(LocalDate.ofInstant(s.end().minusNanos(1), ZoneOffset.UTC));
                }
            }
        }
        assertThat(live).isLessThanOrEqualTo(2);
    }
}
