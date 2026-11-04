package wqs.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.config.ReaderJdbc;
import wqs.config.ReaderTx;
import wqs.domain.PageUnit;
import wqs.domain.ViewKind;
import wqs.read.Segment.Tier;

/**
 * Paged reads assembled from counters and live slices (spec 7.5, 7.6), for both page units: level
 * 0 pages the view's transaction table (TRANSACTION), levels 1 and 2 its high-level page index (HIGH_LEVEL). One
 * counter-assisted offset walk serves both; a per-unit adapter supplies the counter key, the live
 * count and the key query.
 *
 * <p>Every read runs in ONE {@code @ReaderTx} transaction (read-only, REPEATABLE READ, reader
 * pool); each statement inside it goes through the {@code @ReaderJdbc} client, which joins that
 * transaction's connection, so the total, the walk and the fetch or assembly share one snapshot.
 */
@Component
public class ProjectionReader {

    private static final int LEVEL_0 = 0;

    private final JdbcClient readerJdbc;
    private final TransactionTemplate readerTx;
    private final CounterReader counters;
    private final ReadProperties properties;
    private final ReadMetrics metrics;
    private final Map<ViewKind, HighLevelQuery> highLevelQueries = new EnumMap<>(ViewKind.class);

    /**
     * @param highLevelQueries exactly one per view (spec 2.2 rule 4)
     * @throws IllegalStateException if a view has none or several
     */
    public ProjectionReader(
            @ReaderJdbc JdbcClient readerJdbc, @ReaderTx TransactionTemplate readerTx,
            CounterReader counters, ReadProperties properties, ReadMetrics metrics, List<HighLevelQuery> highLevelQueries) {
        this.readerJdbc = readerJdbc;
        this.readerTx = readerTx;
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
     * the walk (see {@link #walk}) finds the page's projected transaction keys, which are then fetched. A page past
     * the end -- including any offset that overflows a long -- is empty with correct totals
     * (spec 7.9).
     */
    public ProjectionPage readLevel0(Level0Query q, Level0Request r) {
        long effective = effectivePageSize(r.pageSize());
        Outcome<TransactionEntry> out = readerTx.execute(status -> {
            JdbcClient tx = readerJdbc;
            Level0Filter f = r.filter();
            if (!r.transactionTypes().isEmpty()) {
                // Matched as text (plan D2): an unknown code matches nothing, no lookup needed.
                f = f.withTypeCodes(r.transactionTypes());
            }
            Walk<TransactionKey> w = walk(tx, transactionUnit(q, f), r.from(), r.to(), r.page(), effective);
            return new Outcome<>(w, w.walked() ? q.fetch(tx, f.userId(), w.keys()) : List.of());
        });

        record(LEVEL_0, q.view(), effective, out.walk());
        long total = out.walk().total();
        long totalPages = total == 0 ? 0 : Math.ceilDiv(total, effective);
        return new ProjectionPage(r.page(), effective, total, totalPages, LEVEL_0, out.entries());
    }

    /** Exact projected transaction count of [from, to), in one REPEATABLE READ transaction. */
    public long total(Level0Query q, Level0Filter f, Instant from, Instant to) {
        return readerTx.execute(status -> segmentCounts(readerJdbc, transactionUnit(q, f), from, to).total());
    }

    /** Per-segment TRANSACTION counts of [from, to): counters for whole days, live for the partial boundary days. */
    public SegmentCounts segmentCounts(
            JdbcClient tx, Level0Query q, Level0Filter f, Instant from, Instant to) {
        return segmentCounts(tx, transactionUnit(q, f), from, to);
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
            HighLevelRequest req, BiFunction<JdbcClient, List<HighLevelKey>, List<R>> assemble) {
        return readHighLevel(highLevelQueries.get(req.view()), req, assemble);
    }

    /**
     * {@link #readHighLevel(HighLevelRequest, BiFunction)} with an explicit query of the
     * request's view; tests observe the walk through it.
     */
    <R> HighLevelPage<R> readHighLevel(
            HighLevelQuery q, HighLevelRequest req, BiFunction<JdbcClient, List<HighLevelKey>, List<R>> assemble) {
        if (q.view() != req.view()) {
            throw new IllegalArgumentException("query for " + q.view() + ", request for " + req.view());
        }
        long effective = effectivePageSize(req.pageSize());
        Outcome<R> out = readerTx.execute(status -> {
            JdbcClient tx = readerJdbc;
            Walk<HighLevelKey> w = walk(
                    tx, highLevelUnit(q, req.filter()), req.from(), req.to(), req.page(), effective);
            return new Outcome<>(w, w.walked() ? assemble.apply(tx, w.keys()) : List.<R>of());
        });

        record(req.level().value(), q.view(), effective, out.walk());
        return new HighLevelPage<>(out.walk().total(), out.entries());
    }

    /** Exact page-index row count of [from, to), in one REPEATABLE READ transaction. */
    public long total(HighLevelQuery q, HighLevelFilter f, Instant from, Instant to) {
        return readerTx.execute(status -> segmentCounts(readerJdbc, highLevelUnit(q, f), from, to).total());
    }

    /** Per-segment HIGH_LEVEL counts of [from, to): counters for whole days, live for the partial boundary days. */
    public SegmentCounts segmentCounts(
            JdbcClient tx, HighLevelQuery q, HighLevelFilter f, Instant from, Instant to) {
        return segmentCounts(tx, highLevelUnit(q, f), from, to);
    }

    // ------------------------------------------------------------------ the walk, per page unit

    /**
     * What the walk needs of one page unit: its counter key (unit, view, read policy, user, product types,
     * transaction types -- always empty for HIGH_LEVEL, whose counters carry none (plan D4) --
     * and excludeCancelled), a live count and an ordered key query over a sub-range.
     */
    private record Unit<K>(
            PageUnit pageUnit, ViewKind view, ReadPolicy policy, long userId, Set<String> productTypes, Set<String> typeCodes,
            boolean excludeCancelled, LiveCount count, KeyQuery<K> keys, Function<K, Instant> occurredAt) {}

    @FunctionalInterface
    private interface LiveCount {
        long count(JdbcClient tx, Instant lo, Instant hi);
    }

    @FunctionalInterface
    private interface KeyQuery<K> {
        List<K> keys(JdbcClient tx, Instant lo, Instant hi, long offset, int limit);
    }

    private static Unit<TransactionKey> transactionUnit(Level0Query q, Level0Filter f) {
        return new Unit<>(PageUnit.TRANSACTION, q.view(), q.policy(), f.userId(), f.productTypes(), f.typeCodes(),
                f.excludeCancelled(),
                (tx, lo, hi) -> q.count(tx, f, lo, hi),
                (tx, lo, hi, offset, limit) -> q.keys(tx, f, lo, hi, offset, limit),
                TransactionKey::occurredAt);
    }

    private static Unit<HighLevelKey> highLevelUnit(HighLevelQuery q, HighLevelFilter f) {
        return new Unit<>(PageUnit.HIGH_LEVEL, q.view(), q.policy(), f.userId(), f.productTypes(), Set.of(),
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

        boolean walked() {
            return residual >= 0;
        }
    }

    /** One read transaction's walk and the entries built from its keys. */
    private record Outcome<E>(Walk<?> walk, List<E> entries) {}

    /**
     * The counter-assisted offset walk (spec 7.6, plan D4), inside the caller's transaction:
     * count every segment of [from, to) and walk them newest-first, to the segment holding the
     * page's first unit and on to the segment holding its last (where the running count from the
     * first reaches residual + limit). ONE ordered key query then runs over exactly those
     * segments -- from the first's end down to the last's start, never below {@code from} --
     * skipping only the residual. Segment counts are exact, so the bounded range holds the
     * page's units in the same order as [from, end) would; units tied on one instant share a day,
     * and so a segment, so the bound never splits a tie. Only the partitions of the page's own
     * days are opened. A page past the end runs no key query.
     */
    private <K> Walk<K> walk(JdbcClient tx, Unit<K> u, Instant from, Instant to, long page, long pageSize) {
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
        // residual + limit <= the units from segment i to the oldest, so j stays inside the array.
        int j = i;
        long through = counts[i];
        while (through < residual + limit) {
            j++;
            through += counts[j];
        }
        Instant lo = max(from, sc.segments().get(j).start());
        List<K> keys = u.keys().keys(tx, lo, sc.segments().get(i).end(), residual, limit);
        Set<YearMonth> months = new HashSet<>();
        for (K k : keys) {
            months.add(YearMonth.from(u.occurredAt().apply(k).atOffset(ZoneOffset.UTC)));
        }
        return new Walk<>(total, sc.counterRows(), residual, keys, months.size());
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
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
     * Counts [from, to) per segment (plan D3). {@link RangeDecomposition#newestFirst} gives at most
     * one DAY run between two LIVE ends; the run's day counters are read in ONE query (absent day
     * = 0) and the run is split newest-first into one segment per active day, each gap of empty
     * days becoming a single zero-count segment. The LIVE ends get a live count. So the list is at
     * most 2 x active days + 3 long, whatever the range's length, and every segment with a
     * non-zero count is one day or a LIVE end -- where the walk lands (plan D4).
     */
    private SegmentCounts segmentCounts(JdbcClient tx, Unit<?> u, Instant from, Instant to) {
        List<Segment> decomposed = RangeDecomposition.newestFirst(from, to);
        // The counters are read FIRST, before any live count: their statement opens the read's
        // snapshot, as it always has (a concurrent commit cannot slip in ahead of it).
        Map<LocalDate, Long> days = Map.of();
        for (Segment s : decomposed) {
            if (s.tier() == Tier.DAY) {
                days = counters.dayCounts(tx, u.policy(), u.userId(), u.view(), u.pageUnit(),
                        u.productTypes(), u.typeCodes(), u.excludeCancelled(),
                        day(s.start()), day(s.end()).minusDays(1));
            }
        }
        List<Segment> segments = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        int counterRows = 0;
        for (Segment s : decomposed) {
            if (s.tier() == Tier.LIVE) {
                segments.add(s);
                counts.add(u.count().count(tx, s.start(), s.end()));
                continue;
            }
            List<LocalDate> active = new ArrayList<>(days.keySet());
            active.sort(Comparator.reverseOrder());
            Instant cursor = s.end();
            for (LocalDate d : active) {
                Instant dayStart = d.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant dayEnd = dayStart.plusSeconds(86_400);
                if (dayEnd.isBefore(cursor)) {
                    segments.add(new Segment(dayEnd, cursor, Tier.DAY));
                    counts.add(0L);
                }
                segments.add(new Segment(dayStart, dayEnd, Tier.DAY));
                counts.add(days.get(d));
                counterRows++;
                cursor = dayStart;
            }
            if (cursor.isAfter(s.start())) {
                segments.add(new Segment(s.start(), cursor, Tier.DAY));
                counts.add(0L);
            }
        }
        return new SegmentCounts(segments, counts.stream().mapToLong(Long::longValue).toArray(), counterRows);
    }

    private static LocalDate day(Instant i) {
        return LocalDate.ofInstant(i, ZoneOffset.UTC);
    }
}
