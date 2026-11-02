package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import wqs.config.ReaderDsl;
import wqs.db.TxTypeRegistry;
import wqs.domain.PageUnit;
import wqs.domain.ViewKind;
import wqs.read.Segment.Tier;

/**
 * Paged reads assembled from counters and live slices (spec 7.5, 7.6), for both page units: level
 * 0 pages the view's item table (ITEM), levels 1 and 2 its high-level page index (HIGH_LEVEL). One
 * counter-assisted offset walk serves both; a per-unit adapter supplies the counter key, the live
 * count and the key query.
 */
@Component
public class ProjectionReader {

    private static final int LEVEL_0 = 0;

    private final DSLContext readerDsl;
    private final TxTypeRegistry txTypes;
    private final CounterReader counters;
    private final ReadProperties properties;
    private final ReadMetrics metrics;
    private final Map<ViewKind, HighLevelQuery> highLevelQueries = new EnumMap<>(ViewKind.class);

    /**
     * @param highLevelQueries exactly one per view (spec 2.2 rule 4)
     * @throws IllegalStateException if a view has none or several
     */
    public ProjectionReader(
            @ReaderDsl DSLContext readerDsl, TxTypeRegistry txTypes, CounterReader counters,
            ReadProperties properties, ReadMetrics metrics, List<HighLevelQuery> highLevelQueries) {
        this.readerDsl = readerDsl;
        this.txTypes = txTypes;
        this.counters = counters;
        this.properties = properties;
        this.metrics = metrics;
        for (HighLevelQuery q : highLevelQueries) {
            if (this.highLevelQueries.put(q.view(), q) != null) {
                throw new IllegalStateException("more than one high-level query for view " + q.view());
            }
        }
        for (ViewKind v : ViewKind.values()) {
            if (!this.highLevelQueries.containsKey(v)) {
                throw new IllegalStateException("no high-level query for view " + v);
            }
        }
    }

    /** The page size a request is served with: the requested one, capped by any ceiling (spec 7.9). */
    public long effectivePageSize(long requested) {
        return properties.maxPageSize() == null ? requested : Math.min(requested, properties.maxPageSize());
    }

    // ------------------------------------------------------------------ level 0

    /**
     * One level-0 page by counter-assisted offset (spec 7.6), in one REPEATABLE READ transaction:
     * the walk (see {@link #walk}) finds the page's item keys, which are then fetched. A page past
     * the end -- including any offset that overflows a long -- is empty with correct totals
     * (spec 7.9).
     */
    public ProjectionPage readLevel0(Level0Query q, Level0Request r) {
        long effective = effectivePageSize(r.pageSize());
        Outcome<ItemEntry> out = readerDsl.transactionResult(cfg -> {
            DSLContext tx = DSL.using(cfg);
            Level0Filter f = r.filter();
            if (!r.transactionTypes().isEmpty()) {
                // Resolved through the reader's own tx (M2): never the writer pool.
                Set<Short> ids = txTypes.idsOf(tx, r.transactionTypes());
                if (ids.isEmpty()) {
                    // Nothing can match: no slice, no totals, no SQL beyond the lookup.
                    return new Outcome<>(Walk.none(), List.of());
                }
                f = f.withTypeIds(ids);
            }
            Walk<ItemKey> w = walk(tx, itemUnit(q, f), r.from(), r.to(), r.page(), effective);
            return new Outcome<>(w, w.walked() ? q.fetch(tx, f.userId(), w.keys()) : List.of());
        });

        record(LEVEL_0, q.view(), effective, out.walk());
        long total = out.walk().total();
        long totalPages = total == 0 ? 0 : Math.ceilDiv(total, effective);
        return new ProjectionPage(r.page(), effective, total, totalPages, LEVEL_0, out.entries());
    }

    /** Exact item count of [from, to), in one REPEATABLE READ transaction. */
    public long total(Level0Query q, Level0Filter f, Instant from, Instant to) {
        return readerDsl.transactionResult(cfg ->
                segmentCounts(DSL.using(cfg), itemUnit(q, f), from, to).total());
    }

    /** Per-segment ITEM counts of [from, to): counters for whole months and days, live for the rest. */
    public SegmentCounts segmentCounts(
            DSLContext tx, Level0Query q, Level0Filter f, Instant from, Instant to) {
        return segmentCounts(tx, itemUnit(q, f), from, to);
    }

    // ------------------------------------------------------------------ levels 1-2

    /**
     * One level-1/2 page of the request's view (ruling R4): the same counter-assisted offset walk
     * as level 0, over the high-level page index and its HIGH_LEVEL counters. The total, the key
     * walk and {@code assemble(tx, keys)} all run inside ONE REPEATABLE READ reader transaction
     * (the phase-4 snapshot rule), so the assembler must read through the {@code tx} it is given.
     * It receives the page's keys in page-index order and is not called for a page past the end,
     * which is empty with the correct total.
     */
    public <R> HighLevelPage<R> readHighLevel(
            HighLevelRequest req, BiFunction<DSLContext, List<HighLevelKey>, List<R>> assemble) {
        return readHighLevel(highLevelQueries.get(req.view()), req, assemble);
    }

    /**
     * {@link #readHighLevel(HighLevelRequest, BiFunction)} with an explicit query of the
     * request's view; tests observe the walk through it.
     */
    <R> HighLevelPage<R> readHighLevel(
            HighLevelQuery q, HighLevelRequest req, BiFunction<DSLContext, List<HighLevelKey>, List<R>> assemble) {
        if (q.view() != req.view()) {
            throw new IllegalArgumentException("query for " + q.view() + ", request for " + req.view());
        }
        long effective = effectivePageSize(req.pageSize());
        Outcome<R> out = readerDsl.transactionResult(cfg -> {
            DSLContext tx = DSL.using(cfg);
            Walk<HighLevelKey> w = walk(
                    tx, highLevelUnit(q, req.filter()), req.from(), req.to(), req.page(), effective);
            return new Outcome<>(w, w.walked() ? assemble.apply(tx, w.keys()) : List.<R>of());
        });

        record(req.level().value(), q.view(), effective, out.walk());
        return new HighLevelPage<>(out.walk().total(), out.entries());
    }

    /** Exact page-index row count of [from, to), in one REPEATABLE READ transaction. */
    public long total(HighLevelQuery q, HighLevelFilter f, Instant from, Instant to) {
        return readerDsl.transactionResult(cfg ->
                segmentCounts(DSL.using(cfg), highLevelUnit(q, f), from, to).total());
    }

    /** Per-segment HIGH_LEVEL counts of [from, to): counters for whole months and days, live for the rest. */
    public SegmentCounts segmentCounts(
            DSLContext tx, HighLevelQuery q, HighLevelFilter f, Instant from, Instant to) {
        return segmentCounts(tx, highLevelUnit(q, f), from, to);
    }

    // ------------------------------------------------------------------ the walk, per page unit

    /**
     * What the walk needs of one page unit: its counter key (unit, view, user, product types,
     * transaction types -- always empty for HIGH_LEVEL, whose counters carry none (plan D4) --
     * and excludeCancelled), a live count and an ordered key query over a sub-range.
     */
    private record Unit<K>(
            PageUnit pageUnit, ViewKind view, long userId, Set<String> productTypes, Set<Short> typeIds,
            boolean excludeCancelled, LiveCount count, KeyQuery<K> keys, Function<K, Instant> occurredAt) {}

    @FunctionalInterface
    private interface LiveCount {
        long count(DSLContext tx, Instant lo, Instant hi);
    }

    @FunctionalInterface
    private interface KeyQuery<K> {
        List<K> keys(DSLContext tx, Instant lo, Instant hi, long offset, int limit);
    }

    private static Unit<ItemKey> itemUnit(Level0Query q, Level0Filter f) {
        return new Unit<>(PageUnit.ITEM, q.view(), f.userId(), f.productTypes(), f.typeIds(),
                f.excludeCancelled(),
                (tx, lo, hi) -> q.count(tx, f, lo, hi),
                (tx, lo, hi, offset, limit) -> q.keys(tx, f, lo, hi, offset, limit),
                ItemKey::occurredAt);
    }

    private static Unit<HighLevelKey> highLevelUnit(HighLevelQuery q, HighLevelFilter f) {
        return new Unit<>(PageUnit.HIGH_LEVEL, q.view(), f.userId(), f.productTypes(), Set.of(),
                f.excludeCancelled(),
                (tx, lo, hi) -> q.count(tx, f, lo, hi),
                (tx, lo, hi, offset, limit) -> q.keys(tx, f, lo, hi, offset, limit),
                HighLevelKey::occurredAt);
    }

    /**
     * What the walk found. {@code residual < 0} when no segment was walked (empty page: no key
     * query ran); {@code partitionsTouched} is the number of distinct UTC months among the keys.
     */
    private record Walk<K>(long total, int counterRows, long residual, List<K> keys, int partitionsTouched) {

        static <K> Walk<K> none() {
            return new Walk<>(0, 0, -1, List.of(), 0);
        }

        boolean walked() {
            return residual >= 0;
        }
    }

    /** One read transaction's walk and the entries built from its keys. */
    private record Outcome<E>(Walk<?> walk, List<E> entries) {}

    /**
     * The counter-assisted offset walk (spec 7.6), inside the caller's transaction: count every
     * segment of [from, to), walk them newest-first to the segment holding the page's first unit,
     * then run ONE ordered key query from that segment's end down to {@code from}, skipping only
     * the residual inside it. A page past the end runs no key query.
     */
    private <K> Walk<K> walk(DSLContext tx, Unit<K> u, Instant from, Instant to, long page, long pageSize) {
        SegmentCounts sc = segmentCounts(tx, u, from, to);
        long total = sc.total();
        long offset = offset(page, pageSize);
        if (offset >= total) {
            return new Walk<>(total, sc.counterRows(), -1, List.of(), 0);
        }
        // offset < total = sum(counts), so the walk stops inside the array.
        long[] counts = sc.counts();
        int i = 0;
        long residual = offset;
        while (residual >= counts[i]) {
            residual -= counts[i];
            i++;
        }
        int limit = (int) Math.min(Math.min(pageSize, total - offset), Integer.MAX_VALUE);
        List<K> keys = u.keys().keys(tx, from, sc.segments().get(i).end(), residual, limit);
        Set<YearMonth> months = new HashSet<>();
        for (K k : keys) {
            months.add(YearMonth.from(u.occurredAt().apply(k).atOffset(ZoneOffset.UTC)));
        }
        return new Walk<>(total, sc.counterRows(), residual, keys, months.size());
    }

    private void record(int level, ViewKind view, long effective, Walk<?> w) {
        metrics.counterRows(w.counterRows());
        if (w.walked()) {
            metrics.residualOffset(w.residual());
        }
        metrics.partitionsTouched(level, w.partitionsTouched());
        metrics.pageSize(view, effective);
        metrics.totalItems(view, w.total());
    }

    /** {@code (page - 1) * pageSize}, or {@link Long#MAX_VALUE} (past any total) on overflow. */
    private static long offset(long page, long pageSize) {
        try {
            return Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Counts each segment of {@link RangeDecomposition#newestFirst}: whole months and days from
     * the unit's counters (absent row = 0), sub-day remainders by a live count. Months are read in
     * one query over their (contiguous) span; days in one query per contiguous run of DAY segments
     * -- at most two, the partial months at either end -- so a multi-year range never fetches the
     * daily rows of the whole months in between (spec 7.5 bounded cost).
     */
    private SegmentCounts segmentCounts(DSLContext tx, Unit<?> u, Instant from, Instant to) {
        List<Segment> segments = RangeDecomposition.newestFirst(from, to);
        Map<LocalDate, Long> months = new HashMap<>();
        Map<LocalDate, Long> days = new HashMap<>();
        for (List<Segment> run : runs(segments)) {
            LocalDate newest = period(run.get(0));
            LocalDate oldest = period(run.get(run.size() - 1));
            if (run.get(0).tier() == Tier.MONTH) {
                months.putAll(counters.monthCounts(tx, u.userId(), u.view(), u.pageUnit(), u.productTypes(),
                        u.typeIds(), u.excludeCancelled(), oldest, newest));
            } else {
                days.putAll(counters.dayCounts(tx, u.userId(), u.view(), u.pageUnit(), u.productTypes(),
                        u.typeIds(), u.excludeCancelled(), oldest, newest));
            }
        }
        long[] counts = new long[segments.size()];
        int counterRows = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.tier() == Tier.LIVE) {
                counts[i] = u.count().count(tx, s.start(), s.end());
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
