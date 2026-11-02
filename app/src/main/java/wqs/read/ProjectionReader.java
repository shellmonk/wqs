package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import wqs.config.ReaderDsl;
import wqs.read.Segment.Tier;

/** Level-0 reads assembled from counters and live slices (spec 7.5). */
@Component
public class ProjectionReader {

    private static final int LEVEL_0 = 0;

    private final DSLContext readerDsl;
    private final CounterReader counters;
    private final ReadProperties properties;
    private final ReadMetrics metrics;

    public ProjectionReader(
            @ReaderDsl DSLContext readerDsl, CounterReader counters, ReadProperties properties, ReadMetrics metrics) {
        this.readerDsl = readerDsl;
        this.counters = counters;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * One level-0 page by counter-assisted offset (spec 7.6), in one REPEATABLE READ transaction:
     * count every segment, walk them newest-first to the segment holding the page's first item,
     * then run ONE ordered key query from that segment's end down to {@code from}, skipping only
     * the residual inside it. A page past the end -- including any offset that overflows a long --
     * is empty with correct totals (spec 7.9).
     */
    public ProjectionPage readLevel0(Level0Query q, Level0Request r) {
        long effective = properties.maxPageSize() == null
                ? r.pageSize() : Math.min(r.pageSize(), properties.maxPageSize());
        Level0Filter f = r.filter();
        Outcome out = readerDsl.transactionResult(cfg -> {
            DSLContext tx = DSL.using(cfg);
            SegmentCounts sc = segmentCounts(tx, q, f, r.from(), r.to());
            long total = sc.total();
            long offset = offset(r.page(), effective);
            if (offset >= total) {
                return new Outcome(total, sc.counterRows(), -1, List.of(), 0);
            }
            // offset < total = sum(counts), so the walk stops inside the array.
            long[] counts = sc.counts();
            int i = 0;
            long residual = offset;
            while (residual >= counts[i]) {
                residual -= counts[i];
                i++;
            }
            int limit = (int) Math.min(Math.min(effective, total - offset), Integer.MAX_VALUE);
            List<ItemKey> keys = q.keys(tx, f, r.from(), sc.segments().get(i).end(), residual, limit);
            return new Outcome(total, sc.counterRows(), residual, q.fetch(tx, f.userId(), keys), months(keys));
        });

        String endpoint = q.view().name().toLowerCase(Locale.ROOT);
        metrics.counterRows(out.counterRows());
        if (out.residual() >= 0) {
            metrics.residualOffset(out.residual());
        }
        metrics.partitionsTouched(LEVEL_0, out.partitionsTouched());
        metrics.pageSize(endpoint, effective);
        metrics.totalItems(endpoint, out.total());
        long totalPages = out.total() == 0 ? 0 : Math.ceilDiv(out.total(), effective);
        return new ProjectionPage(r.page(), effective, out.total(), totalPages, LEVEL_0, out.items());
    }

    /** What one read transaction produced; {@code residual < 0} when no segment was walked. */
    private record Outcome(long total, int counterRows, long residual, List<ItemEntry> items, int partitionsTouched) {}

    /** {@code (page - 1) * pageSize}, or {@link Long#MAX_VALUE} (past any total) on overflow. */
    private static long offset(long page, long pageSize) {
        try {
            return Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Distinct UTC months among the keys -- the item partitions the page's rows live in. */
    private static int months(List<ItemKey> keys) {
        Set<YearMonth> months = new HashSet<>();
        for (ItemKey k : keys) {
            months.add(YearMonth.from(k.occurredAt().atOffset(ZoneOffset.UTC)));
        }
        return months.size();
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
