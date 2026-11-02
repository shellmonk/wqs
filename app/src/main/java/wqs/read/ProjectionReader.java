package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import wqs.config.ReaderDsl;
import wqs.read.Segment.Tier;

/** Level-0 reads assembled from counters and live slices (spec 7.5). */
@Component
public class ProjectionReader {

    private final DSLContext readerDsl;
    private final CounterReader counters;

    public ProjectionReader(@ReaderDsl DSLContext readerDsl, CounterReader counters) {
        this.readerDsl = readerDsl;
        this.counters = counters;
    }

    /** Exact item count of [from, to), in one REPEATABLE READ transaction. */
    public long total(Level0Query q, Level0Filter f, Instant from, Instant to) {
        return readerDsl.transactionResult(cfg -> segmentCounts(DSL.using(cfg), q, f, from, to).total());
    }

    /**
     * Counts each segment of {@link RangeDecomposition#newestFirst}: whole months and days from
     * the counters (one query per tier over that tier's span; absent row = 0), sub-day
     * remainders by a live count.
     */
    public SegmentCounts segmentCounts(
            DSLContext tx, Level0Query q, Level0Filter f, Instant from, Instant to) {
        List<Segment> segments = RangeDecomposition.newestFirst(from, to);
        Map<LocalDate, Long> months = counterSpan(tx, q, f, segments, Tier.MONTH);
        Map<LocalDate, Long> days = counterSpan(tx, q, f, segments, Tier.DAY);
        long[] counts = new long[segments.size()];
        int counterRows = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.tier() == Tier.LIVE) {
                counts[i] = q.count(tx, f, s.start(), s.end());
                continue;
            }
            Long stored = (s.tier() == Tier.MONTH ? months : days).get(period(s));
            if (stored != null) {
                counts[i] = stored;
                counterRows++;
            }
        }
        return new SegmentCounts(segments, counts, counterRows);
    }

    /**
     * One counter query spanning every segment of {@code tier}; empty when there is none. The
     * span may include periods covered by another tier -- callers pick only their segments.
     */
    private Map<LocalDate, Long> counterSpan(
            DSLContext tx, Level0Query q, Level0Filter f, List<Segment> segments, Tier tier) {
        LocalDate first = null;
        LocalDate last = null;
        for (Segment s : segments) {
            if (s.tier() != tier) {
                continue;
            }
            LocalDate p = period(s);
            first = first == null || p.isBefore(first) ? p : first;
            last = last == null || p.isAfter(last) ? p : last;
        }
        if (first == null) {
            return Map.of();
        }
        return tier == Tier.MONTH
                ? counters.months(tx, q.view(), f, first, last)
                : counters.days(tx, q.view(), f, first, last);
    }

    private static LocalDate period(Segment s) {
        return LocalDate.ofInstant(s.start(), ZoneOffset.UTC);
    }
}
