package wqs.gen;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.read.HighLevelFilter;
import wqs.read.HighLevelRequest;
import wqs.read.Level0Filter;
import wqs.read.Level0Request;

/**
 * Read-side generators (spec 8.1): small level-0 histories shaped to stress the counter/live
 * split -- sparse days and months, ties on "occurredAt", transactions exactly on day and month
 * boundaries, /cc-only payments, null and non-null products, and cancels on both sides of their
 * originals in arrival order -- plus boundary-heavy level-0 queries over them.
 */
public final class ReadGenerators {

    /** Transactions lie in [WINDOW_START, WINDOW_END); partitions 2033-12 through 2034-03 cover it. */
    public static final Instant WINDOW_START = Instant.parse("2033-12-20T00:00:00Z");
    public static final Instant WINDOW_END = Instant.parse("2034-03-10T00:00:00Z");

    /** Queries range 10 days beyond the data on both sides. */
    public static final Instant QUERY_START = WINDOW_START.minus(10, ChronoUnit.DAYS);
    public static final Instant QUERY_END = WINDOW_END.plus(10, ChronoUnit.DAYS);

    /** Month boundaries inside the window; always part of the boundary pool. */
    static final List<Instant> MONTH_BOUNDARIES = List.of(
            Instant.parse("2034-01-01T00:00:00Z"),
            Instant.parse("2034-02-01T00:00:00Z"),
            Instant.parse("2034-03-01T00:00:00Z"));

    private static final int WINDOW_DAYS = (int) ChronoUnit.DAYS.between(WINDOW_START, WINDOW_END);
    private static final int QUERY_DAYS = (int) ChronoUnit.DAYS.between(QUERY_START, QUERY_END);

    /**
     * Days next to a month boundary (Dec 31 / Jan 1, Jan 31 / Feb 1, Feb 28 / Mar 1), as offsets
     * from WINDOW_START. Dense days are biased towards them so whole-month and partial-month
     * segments both see data.
     */
    private static final List<Integer> EDGE_DAYS = List.of(11, 12, 42, 43, 70, 71);

    private static final int MAX_TRANSACTIONS = 150;

    /**
     * {@code productTypes} and {@code transactionTypes} (codes) are empty (no filter) or a subset
     * of the values the histories use plus one value no transaction has.
     */
    public record ReadQuery(
            ViewKind view, long userId, Instant from, Instant to, boolean excludeCancelled,
            Set<String> productTypes, Set<String> transactionTypes) {

        /** The filter with the product list; the type list stays codes, see {@link #request}. */
        public Level0Filter filter() {
            return new Level0Filter(userId, productTypes, Set.of(), excludeCancelled);
        }

        public Level0Request request(long page, long pageSize) {
            return new Level0Request(filter(), transactionTypes, from, to, page, pageSize);
        }

        /** The level-1/2 filter: product list and excludeCancelled; types never page at L1/L2 (spec 4.1 (b)). */
        public HighLevelFilter highLevelFilter() {
            return new HighLevelFilter(userId, productTypes, excludeCancelled);
        }

        public HighLevelRequest highLevelRequest(AggregationLevel level, long page, long pageSize) {
            return new HighLevelRequest(view, level, highLevelFilter(), from, to, page, pageSize);
        }
    }

    /** Product types the histories use, and one no transaction has. */
    static final List<String> PRODUCT_TYPE_POOL = List.of("keno", "lottery", "absent-product");
    /** Type codes the histories use (PLAY, WIN, NEUTRAL, CANCEL), and one no table has. */
    static final List<String> TYPE_CODE_POOL =
            List.of("TT_BUY", "PRIZE", "TT_TRANSFER_OUT", "TT_CANCEL", "TT_ABSENT_FILTER_CODE");

    private static Arbitrary<Set<String>> listFilter(List<String> pool) {
        return Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.just(Set.<String>of())),
                Tuple.of(6, Arbitraries.of(pool).set().ofMinSize(1).ofMaxSize(pool.size())));
    }

    // ------------------------------------------------------------------ histories

    private enum TimeKind { BOUNDARY, TIE, CLUSTER }

    /** How one original transaction picks its "occurredAt"; resolved against the transactions before it. */
    private record TimeSpec(TimeKind kind, int pick, int secondOfDay) {}

    /** A cancel of the transaction: its delay after the original and its place in arrival order. */
    private record CancelSpec(long delaySeconds, boolean beforeOriginal, int positionPick) {}

    /** {@code session < 0}: unsessioned; otherwise the slot of the user's session it joins. */
    private record TransactionSpec(
            long userId, TimeSpec time, SourceKind source, String[] product, CancelSpec cancel,
            String detail, long amountCents, int session) {}

    /** Users skewed {1: 60%, 2: 30%, 3: 10%}; user 4 never has data. */
    private static Arbitrary<Long> historyUsers() {
        return Arbitraries.frequency(Tuple.of(6, 1L), Tuple.of(3, 2L), Tuple.of(1, 3L));
    }

    private static Arbitrary<TimeSpec> timeSpecs(int boundary, int tie, int cluster) {
        Arbitrary<Integer> picks = Arbitraries.integers().between(0, 10_000);
        Arbitrary<Integer> seconds = Arbitraries.integers().between(0, 86_399);
        return Arbitraries.frequencyOf(
                Tuple.of(boundary, picks.map(p -> new TimeSpec(TimeKind.BOUNDARY, p, 0))),
                Tuple.of(tie, picks.map(p -> new TimeSpec(TimeKind.TIE, p, 0))),
                Tuple.of(cluster, Combinators.combine(picks, seconds)
                        .as((p, s) -> new TimeSpec(TimeKind.CLUSTER, p, s))));
    }

    /**
     * Delay of a cancel after its original: a tie (0) a fifth of the time, otherwise seconds,
     * hours or days, so cancels often land in a different day or month than their original.
     */
    private static Arbitrary<CancelSpec> cancelSpecs() {
        Arbitrary<Long> delays = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(0L)),
                Tuple.of(1, Arbitraries.longs().between(1, 120)),
                Tuple.of(1, Arbitraries.longs().between(3_600, 86_400)),
                Tuple.of(1, Arbitraries.longs().between(86_400, 20 * 86_400L)));
        return Combinators.combine(
                        delays,
                        Arbitraries.frequency(Tuple.of(1, true), Tuple.of(2, false)),
                        Arbitraries.integers().between(0, 10_000))
                .as(CancelSpec::new);
    }

    private static Arbitrary<TransactionSpec> transactionSpecs(Arbitrary<TimeSpec> times, Arbitrary<Integer> sessions) {
        return Combinators.combine(
                        historyUsers(),
                        times,
                        Arbitraries.frequency(
                                Tuple.of(5, SourceKind.TRANSACTION),
                                Tuple.of(2, SourceKind.CLAIMABLE_PRIZE),
                                Tuple.of(3, SourceKind.PAYMENT)),
                        Arbitraries.<String[]>of(new String[] {"P11", "keno"},
                                        new String[] {"P13", "lottery"})
                                .injectNull(0.3),
                        cancelSpecs().injectNull(0.8),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(4)
                                .map(c -> "{\"gameCode\": \"" + c + "\"}").injectNull(0.5),
                        // Never zero: a zero-amount transaction is locator-only (spec 4.1) and in no view.
                        Arbitraries.longs().between(-50_000, 50_000).filter(cents -> cents != 0),
                        sessions)
                .as(TransactionSpec::new);
    }

    /** 2-4 dense days per user, offsets from WINDOW_START, a quarter next to a month boundary. */
    private static Arbitrary<List<Integer>> denseDays() {
        Arbitrary<Integer> day = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.of(EDGE_DAYS)),
                Tuple.of(3, Arbitraries.integers().between(0, WINDOW_DAYS - 1)));
        return day.list().ofMinSize(2).ofMaxSize(4);
    }

    /**
     * 20-150 level-0 transactions (at most 125 originals plus their cancels, capped at 150). Every
     * "batchId" is null: level 0 ignores sessions, and this keeps rollups out of the way.
     */
    public static Arbitrary<List<ProjectionTransaction>> history() {
        return Combinators.combine(
                        denseDays(), denseDays(), denseDays(),
                        transactionSpecs(timeSpecs(15, 15, 70), Arbitraries.just(-1)).list().ofMinSize(20).ofMaxSize(125))
                .as((d1, d2, d3, specs) -> build(Map.of(1L, d1, 2L, d2, 3L, d3), specs));
    }

    /** Session slots per user in {@link #highLevelHistory()}. */
    private static final int SESSION_SLOTS = 10;

    /**
     * 20-150 projected transactions for level-1/2 paging: like {@link #history()}, but about half the
     * transactions and claimable prizes join one of each user's {@value #SESSION_SLOTS} sessions
     * ("u{user}-b{slot}"; payments never do), and times are tie-heavy -- month boundaries and
     * reused instants -- so page-index rows share "occurredAt" across kinds (a session, kind 0,
     * beside unsessioned transactions of kinds 1, 3 and 4) and within one kind, and the "kind" and
     * "refId" tie-breaks decide page boundaries. Cancels carry no "batchId"; cancelling every
     * live transaction of a session hides its row, so excludeCancelled filters sessions too.
     */
    public static Arbitrary<List<ProjectionTransaction>> highLevelHistory() {
        Arbitrary<Integer> sessions = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(-1)),
                Tuple.of(1, Arbitraries.integers().between(0, SESSION_SLOTS - 1)));
        return Combinators.combine(
                        denseDays(), denseDays(), denseDays(),
                        transactionSpecs(timeSpecs(30, 35, 35), sessions).list().ofMinSize(20).ofMaxSize(125))
                .as((d1, d2, d3, specs) -> build(Map.of(1L, d1, 2L, d2, 3L, d3), specs));
    }

    private record Placed(ProjectionTransaction transaction, double order) {}

    private static List<ProjectionTransaction> build(Map<Long, List<Integer>> dense, List<TransactionSpec> specs) {
        List<ProjectionTransaction> originals = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            TransactionSpec s = specs.get(i);
            Instant at = resolveTime(s, dense.get(s.userId()), originals);
            // Payments are never sessioned (spec 7.4: an operator-only, unsessioned source).
            String batchId = s.session() < 0 || s.source() == SourceKind.PAYMENT
                    ? null : "u" + s.userId() + "-b" + s.session();
            originals.add(new ProjectionTransaction(
                    s.userId(), s.source(), "h" + i, at,
                    s.product() == null ? null : s.product()[0],
                    s.product() == null ? null : s.product()[1],
                    batchId, batchId == null ? null : batchId + "-p" + (i % 2),
                    typeOf(s.source()), BigDecimal.valueOf(s.amountCents(), 4), "EUR", null, null,
                    s.detail()));
        }

        List<Placed> placed = new ArrayList<>();
        int total = originals.size();
        for (int i = 0; i < originals.size(); i++) {
            ProjectionTransaction o = originals.get(i);
            placed.add(new Placed(o, i));
            CancelSpec c = specs.get(i).cancel();
            if (c == null || total >= MAX_TRANSACTIONS) {
                continue;
            }
            Instant at = o.occurredAt().plusSeconds(c.delaySeconds());
            if (!at.isBefore(WINDOW_END)) {
                at = WINDOW_END.minusSeconds(1);
            }
            ProjectionTransaction cancel = new ProjectionTransaction(
                    o.userId(), o.source(), o.sourceId() + "-c", at, o.productId(), o.productType(),
                    null, null, TxTypes.CANCEL, o.amount().negate(), o.currency(), null, o.sourceId(), null);
            // Arrival position strictly before (at a random earlier slot) or after the original.
            double order = c.beforeOriginal()
                    ? (c.positionPick() % (i + 1)) - 0.5
                    : i + 0.5 + (c.positionPick() % (originals.size() - i));
            placed.add(new Placed(cancel, order));
            total++;
        }
        placed.sort(Comparator.comparingDouble(Placed::order));
        return placed.stream().map(Placed::transaction).toList();
    }

    private static Instant resolveTime(TransactionSpec s, List<Integer> dense, List<ProjectionTransaction> earlier) {
        TimeSpec t = s.time();
        switch (t.kind()) {
            case BOUNDARY -> {
                // Half the time a month boundary, otherwise the start of any day in the window.
                if (t.pick() % 2 == 0) {
                    return MONTH_BOUNDARIES.get((t.pick() / 2) % MONTH_BOUNDARIES.size());
                }
                return WINDOW_START.plus((t.pick() / 2) % WINDOW_DAYS, ChronoUnit.DAYS);
            }
            case TIE -> {
                // Reuse an earlier transaction's instant -- the same user's when there is one.
                List<Instant> same = earlier.stream()
                        .filter(e -> e.userId() == s.userId()).map(ProjectionTransaction::occurredAt).toList();
                List<Instant> pool = same.isEmpty()
                        ? earlier.stream().map(ProjectionTransaction::occurredAt).toList() : same;
                if (!pool.isEmpty()) {
                    return pool.get(t.pick() % pool.size());
                }
                return cluster(dense, t.pick(), t.secondOfDay());
            }
            default -> {
                return cluster(dense, t.pick(), t.secondOfDay());
            }
        }
    }

    private static Instant cluster(List<Integer> dense, int pick, int secondOfDay) {
        int day = dense.get(pick % dense.size());
        return WINDOW_START.plus(day, ChronoUnit.DAYS).plusSeconds(secondOfDay);
    }

    private static String typeOf(SourceKind source) {
        return switch (source) {
            case TRANSACTION -> TxTypes.PLAY;
            case CLAIMABLE_PRIZE -> TxTypes.WIN;
            default -> TxTypes.NEUTRAL;
        };
    }

    // ------------------------------------------------------------------ queries

    /** Boundary-heavy instants over [QUERY_START, QUERY_END]. */
    private static Arbitrary<Instant> queryInstants() {
        return Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.of(MONTH_BOUNDARIES)),
                Tuple.of(3, Arbitraries.integers().between(0, QUERY_DAYS)
                        .map(d -> QUERY_START.plus(d, ChronoUnit.DAYS))),
                Tuple.of(4, Arbitraries.longs().between(0, ChronoUnit.SECONDS.between(QUERY_START, QUERY_END))
                        .map(QUERY_START::plusSeconds)));
    }

    /** A sub-day instant pair inside one UTC day, either end possibly on the day boundary. */
    private static Arbitrary<Instant[]> sameDay() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, QUERY_DAYS - 1),
                        Arbitraries.integers().between(0, 86_400),
                        Arbitraries.integers().between(0, 86_400))
                .as((d, a, b) -> {
                    Instant day = QUERY_START.plus(d, ChronoUnit.DAYS);
                    return new Instant[] {day.plusSeconds(Math.min(a, b)), day.plusSeconds(Math.max(a, b))};
                });
    }

    /** A pair inside one UTC month (Dec 2033 - Mar 2034), ends often on day boundaries. */
    private static Arbitrary<Instant[]> sameMonth() {
        Arbitrary<Integer> offsets = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.integers().between(0, 31).map(d -> d * 86_400)),
                Tuple.of(1, Arbitraries.integers().between(0, 31 * 86_400)));
        return Combinators.combine(Arbitraries.integers().between(0, 3), offsets, offsets)
                .as((m, a, b) -> {
                    LocalDate first = LocalDate.of(2033, 12, 1).plusMonths(m);
                    Instant start = first.atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant end = first.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant x = min(start.plusSeconds(a), end);
                    Instant y = min(start.plusSeconds(b), end);
                    return x.isAfter(y) ? new Instant[] {y, x} : new Instant[] {x, y};
                });
    }

    private static Arbitrary<Instant[]> ranges() {
        return Arbitraries.frequencyOf(
                Tuple.of(10, queryInstants().map(i -> new Instant[] {i, i})),
                Tuple.of(10, sameDay()),
                Tuple.of(15, sameMonth()),
                Tuple.of(65, Combinators.combine(queryInstants(), queryInstants())
                        .as((a, b) -> a.isAfter(b) ? new Instant[] {b, a} : new Instant[] {a, b})));
    }

    public static Arbitrary<ReadQuery> queries() {
        return Combinators.combine(
                        Arbitraries.of(ViewKind.values()),
                        Arbitraries.of(1L, 2L, 3L, 4L),
                        ranges(),
                        Arbitraries.of(true, false),
                        listFilter(PRODUCT_TYPE_POOL),
                        listFilter(TYPE_CODE_POOL))
                .as((v, u, r, x, p, t) -> new ReadQuery(v, u, r[0], r[1], x, p, t));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private ReadGenerators() {}
}
