package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
     * the counters (absent row = 0), sub-day remainders by a live count. Months are read in one
     * query over their (contiguous) span; days in one query per contiguous run of DAY segments --
     * at most two, the partial months at either end -- so a multi-year range never fetches the
     * daily rows of the whole months in between (spec 7.5 bounded cost).
     */
    public SegmentCounts segmentCounts(
            DSLContext tx, Level0Query q, Level0Filter f, Instant from, Instant to) {
        List<Segment> segments = RangeDecomposition.newestFirst(from, to);
        Map<LocalDate, Long> months = new HashMap<>();
        Map<LocalDate, Long> days = new HashMap<>();
        for (List<Segment> run : runs(segments)) {
            LocalDate newest = period(run.get(0));
            LocalDate oldest = period(run.get(run.size() - 1));
            if (run.get(0).tier() == Tier.MONTH) {
                months.putAll(counters.months(tx, q.view(), f, oldest, newest));
            } else {
                days.putAll(counters.days(tx, q.view(), f, oldest, newest));
            }
        }
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

    /** Maximal runs of adjacent MONTH or DAY segments (same tier), in list order; LIVE skipped. */
    private static List<List<Segment>> runs(List<Segment> segments) {
        List<List<Segment>> out = new ArrayList<>();
        List<Segment> current = null;
        for (Segment s : segments) {
            if (s.tier() == Tier.LIVE) {
                current = null;
                continue;
            }
            if (current == null || current.get(0).tier() != s.tier()) {
                current = new ArrayList<>();
                out.add(current);
            }
            current.add(s);
        }
        return out;
    }

    private static LocalDate period(Segment s) {
        return LocalDate.ofInstant(s.start(), ZoneOffset.UTC);
    }
}
