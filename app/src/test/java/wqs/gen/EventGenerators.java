package wqs.gen;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;

/**
 * Generators shaped like production data (spec 8.1). Volumes are power-law: a small number of
 * users hold a disproportionate share of rows, and transactions cluster into sessions. Uniformly
 * random users would exercise none of that clustering.
 */
public final class EventGenerators {

    /**
     * Every generated timestamp lies in [WINDOW_START, WINDOW_END + a few minutes], which
     * straddles the March/April 2034 boundary, so re-anchors move rollups, page-index rows and
     * HIGH_LEVEL counters across months as well as days. Callers need partitions for
     * YearMonth.from(WINDOW_START) and the month after.
     */
    public static final Instant WINDOW_START = Instant.parse("2034-03-29T00:00:00Z");
    public static final Instant WINDOW_END = Instant.parse("2034-04-02T00:00:00Z");

    /** Session starts are drawn from a day after WINDOW_START, leaving room for a day-earlier item. */
    private static final Instant SESSION_START_MIN = WINDOW_START.plus(1, ChronoUnit.DAYS);

    /*
     * The product pool, as (productId, productType) -- the {@link Products} pairs. A session's
     * pair is its FIRST item's (plan D6), so the pool deliberately makes the two orders DISAGREE:
     * P03 < P07 < P11 < P13 but "arcade" (P13) < "bingo" (P07) < "instants" (P11) < "lottery"
     * (P03). A per-column LEAST would pair P03 with "arcade" -- a product no item has -- and the
     * oracles would catch it. P11 is eInstant ("instants", the default wqs.einstant.product-types),
     * so every non-cancellation P11 item stays sessioned (P-7); cancels may drop the session.
     */
    private static final String[] P03 = {Products.id(3), Products.type(3)};
    private static final String[] P07 = {Products.id(7), Products.type(7)};
    private static final String[] P11 = {Products.id(11), Products.type(11)};
    private static final String[] P13 = {Products.id(13), Products.type(13)};
    private static final String[] NO_PRODUCT = {null, null};

    /** Heavily skewed: user 1 appears far more often than user 20. */
    public static Arbitrary<Long> users() {
        return Arbitraries.frequency(
                net.jqwik.api.Tuple.of(50, 1L),
                net.jqwik.api.Tuple.of(20, 2L),
                net.jqwik.api.Tuple.of(10, 3L),
                net.jqwik.api.Tuple.of(5, 4L),
                net.jqwik.api.Tuple.of(1, 5L));
    }

    /**
     * Non-session sources that get a sourceId deliberately COLLIDING with a TRANSACTION's sourceId
     * (same user). The locator key is (source, sourceId), so these must all be applied; a gate
     * keyed on sourceId alone would silently drop them. LIMIT is excluded (no producer yet).
     */
    public static Arbitrary<java.util.Set<SourceKind>> collidingSources() {
        return Arbitraries.of(SourceKind.PAYMENT, SourceKind.MANUAL_ADJUSTMENT, SourceKind.CLAIMABLE_PRIZE)
                .set().ofMinSize(1).ofMaxSize(3);
    }

    /** Null about half the time, otherwise a small JSON object, so JSONB round-trips are compared. */
    private static Arbitrary<String> details() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6)
                .map(code -> "{\"gameCode\": \"" + code + "\"}")
                .injectNull(0.5);
    }

    /**
     * The currency of session 0 (every item of it, the /cc-only adjustments included) and of the
     * claimable prize's session: EUR half the time, otherwise another one, so sessions in other
     * currencies are exercised. A session is single-currency (plan D6): mixing currencies within
     * one would dead-letter whichever item arrived second, which depends on arrival order.
     */
    private static Arbitrary<String> joiningCurrencies() {
        return Arbitraries.frequency(
                net.jqwik.api.Tuple.of(2, "EUR"),
                net.jqwik.api.Tuple.of(1, "USD"),
                net.jqwik.api.Tuple.of(1, "AUD"));
    }

    private static Arbitrary<Instant> timestamps() {
        long spanSeconds = ChronoUnit.SECONDS.between(SESSION_START_MIN, WINDOW_END);
        return Arbitraries.longs().between(0, spanSeconds).map(SESSION_START_MIN::plusSeconds);
    }

    private static Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(-50_000, 50_000)
                .map(cents -> BigDecimal.valueOf(cents, 4));
    }

    /**
     * A sequence of items clustered into sessions: each session gets a batchId, a product, and
     * between 1 and 4 plays, each play 1 to 3 transactions.
     *
     * A session's key is (startedAt, first item's productType) (plan D6), so products vary to make
     * the key depend on arrival order on every path: session 0 is joined by a /cc-only adjustment
     * a day earlier at product P07 (re-anchor: start AND product move) and one after its last
     * transaction at product P03 (must NOT relabel it); odd sessions open with a product-less
     * play tied, at the same instant, with a lower-sourceId /cc-only adjustment at P03 (the
     * tie-break relabels the session from no product, or never, depending on order). Every
     * session also has a zero-amount prize step at its first instant (locator-only, D8). Session
     * 0 and the prize session are in EUR, USD or AUD; everything else in EUR.
     */
    public static Arbitrary<List<ProjectionItem>> itemSequences() {
        return Combinators.combine(
                        users(),
                        Arbitraries.integers().between(1, 4),   // sessions per user
                        Arbitraries.integers().between(1, 4),   // plays per session
                        Arbitraries.integers().between(1, 3),   // transactions per play
                        timestamps(),
                        amounts(),
                        collidingSources(),
                        Combinators.combine(details(), joiningCurrencies())
                                .as(net.jqwik.api.Tuple::of))
                .as(EventGenerators::buildSequence);
    }

    /**
     * {@link #itemSequences()} with cancellations (spec 8.1 requires cancels on both sides of their
     * originals). Every cancel targets an item of its OWN source and user (spec 6.3, R86) -- any
     * source, so unsessioned payments, play-less /cc-only adjustments and the cross-source
     * sourceId collisions are cancelled too.
     *
     * <ul>
     *   <li>A third of sessions (decided once per session) have EVERY item cancelled, /cc-only
     *       ones included, so they end hidden in both views. Measured over 1000 samples: of the
     *       sessions with two or more items, 34% end fully cancelled, 51% partially cancelled
     *       (most of those across several plays) and 15% untouched. Interleaved with their
     *       originals (and arriving in either order), the cancels hide a session's rows and a
     *       later live item revives them, repeatedly.</li>
     *   <li>Otherwise each item is cancelled with probability 1/4.</li>
     *   <li>About a third of cancels are placed BEFORE their original, at a random earlier
     *       position -- often in an earlier batch, so they park and drain across batches.</li>
     *   <li>About one cancelled item in eight gets a second, distinct cancel (cancelledBy keeps
     *       the smallest id whatever the order).</li>
     *   <li>Half the cancels carry their original's batchId and subBatchId, as a producer might:
     *       a cancellation row must never roll up or count as a session's surviving item.</li>
     * </ul>
     */
    public static Arbitrary<List<ProjectionItem>> itemSequencesWithCancels() {
        return Combinators.combine(itemSequences(), Arbitraries.longs())
                .as(EventGenerators::withCancels);
    }

    private static List<ProjectionItem> withCancels(List<ProjectionItem> items, long seed) {
        java.util.Random random = new java.util.Random(seed);
        // Decided once per distinct session, in first-seen order (deterministic for a seed).
        java.util.Set<String> sessions = new java.util.LinkedHashSet<>();
        for (ProjectionItem item : items) {
            if (item.batchId() != null) {
                sessions.add(item.batchId());
            }
        }
        java.util.Set<String> fullyCancelled = new java.util.HashSet<>();
        for (String batchId : sessions) {
            if (random.nextInt(3) == 0) {
                fullyCancelled.add(batchId);
            }
        }

        List<ProjectionItem> result = new ArrayList<>();
        for (ProjectionItem item : items) {
            boolean cancelIt = (item.batchId() != null && fullyCancelled.contains(item.batchId()))
                    || random.nextInt(4) == 0;
            result.add(item);
            if (!cancelIt) {
                continue;
            }
            int cancels = random.nextInt(8) == 0 ? 2 : 1;
            for (int c = 1; c <= cancels; c++) {
                // Usually the reversal (a zero-amount item's cancel is then zero-amount too, and
                // locator-only); sometimes not: a non-zero cancel of a zero-amount item is
                // projected and marks nothing, a zero-amount cancel of a live item still cancels it.
                BigDecimal amount = item.amount().negate();
                if (random.nextInt(4) == 0) {
                    amount = item.amount().signum() == 0 ? new BigDecimal("-1.0000") : BigDecimal.ZERO.setScale(4);
                }
                ProjectionItem cancel = cancelOf(item, "-cancel" + c, random.nextBoolean(), amount);
                if (random.nextInt(3) == 0) {
                    // Before its original: anywhere earlier, so often in an earlier batch.
                    int originalAt = result.size() - 1;
                    result.add(random.nextInt(originalAt + 1), cancel);
                } else {
                    result.add(cancel);
                }
            }
        }
        return result;
    }

    /**
     * Half the cancels drop the session, eInstant ones included: a cancel is exempt from the
     * eInstant rule (it never rolls up or gets a page row), and must still cancel its target.
     */
    private static ProjectionItem cancelOf(ProjectionItem item, String suffix, boolean grouped, BigDecimal amount) {
        return new ProjectionItem(
                item.userId(), item.source(), item.sourceId() + suffix,
                item.occurredAt().plusSeconds(90), item.productId(), item.productType(),
                grouped ? item.batchId() : null, grouped ? item.subBatchId() : null,
                TxTypes.CANCEL, amount, item.currency(), null,
                item.sourceId(), null);
    }

    private static List<ProjectionItem> buildSequence(
            long userId, int sessions, int plays, int txPerPlay, Instant start, BigDecimal amount,
            java.util.Set<SourceKind> colliding,
            net.jqwik.api.Tuple.Tuple2<String, String> detailAndCurrency) {
        String detail = detailAndCurrency.get1();
        String joiningCurrency = detailAndCurrency.get2();
        List<ProjectionItem> items = new ArrayList<>();
        int sequence = 0;

        for (int s = 0; s < sessions; s++) {
            String batchId = "u" + userId + "-s" + s;
            String[] sessionProduct = s % 2 == 0 ? P11 : P13;
            String currency = s == 0 ? joiningCurrency : "EUR";
            Instant firstAt = start.plusSeconds((sequence + 1) * 7L);
            for (int p = 0; p < plays; p++) {
                String[] product = s % 2 == 1 && p == 0 ? NO_PRODUCT : sessionProduct;
                String subBatchId = batchId + "-p" + p;
                for (int t = 0; t < txPerPlay; t++) {
                    items.add(new ProjectionItem(
                            userId,
                            SourceKind.TRANSACTION,
                            batchId + "-t" + (sequence++),
                            start.plusSeconds(sequence * 7L),
                            product[0],
                            product[1],
                            batchId,
                            subBatchId,
                            t == 0 ? TxTypes.PLAY : TxTypes.WIN,
                            amount,
                            currency,
                            null,
                            null,
                            detail));
                }
            }
            // A losing prize step: zero-amount, so locator-only (spec 4.1, D8). It sits AT the
            // session's first instant with the session's play, yet must never label or count.
            items.add(new ProjectionItem(
                    userId, SourceKind.TRANSACTION, batchId + "-z", firstAt, P07[0], P07[1],
                    batchId, batchId + "-p0", TxTypes.WIN, BigDecimal.ZERO.setScale(4), currency,
                    null, null, null));
            if (s % 2 == 1) {
                // A tie with the session's first transaction (product-less): same instant, lower
                // sourceId ("-a" < "-t"), so this /cc-only item is the session's first item and
                // its pair labels the session, whichever arrives first.
                items.add(new ProjectionItem(
                        userId, SourceKind.MANUAL_ADJUSTMENT, batchId + "-a", firstAt, P03[0], P03[1],
                        batchId, null, TxTypes.NEUTRAL, amount, currency, null, null, null));
            }
        }

        // /cc-only items joining the first session. Whichever of these and the session's
        // TRANSACTIONs arrives first decides which view's page-index row is created first and
        // which is materialised later. The early one, a day before the session at a lower
        // product, re-anchors the session across a UTC day (and a month when the session starts
        // on April 1) while changing its product; the late one, after the session's last
        // transaction at a lower product still, changes only the product.
        items.add(new ProjectionItem(
                userId, SourceKind.MANUAL_ADJUSTMENT, "u" + userId + "-adj" + sequence,
                start.minus(1, ChronoUnit.DAYS), P07[0], P07[1],
                "u" + userId + "-s0", null, TxTypes.NEUTRAL, amount, joiningCurrency, null, null, null));
        items.add(new ProjectionItem(
                userId, SourceKind.MANUAL_ADJUSTMENT, "u" + userId + "-adjlate" + sequence,
                start.plusSeconds(sequence * 7L + 5), P03[0], P03[1],
                "u" + userId + "-s0", null, TxTypes.NEUTRAL, amount, joiningCurrency, null, null, null));

        // Unsessioned items: payments carry no batchId (spec 7.4).
        items.add(new ProjectionItem(
                userId, SourceKind.PAYMENT, "u" + userId + "-pay" + sequence,
                start.plusSeconds(sequence * 7L + 1), null, null, null, null,
                TxTypes.NEUTRAL, amount.abs(), "EUR", null, null, null));

        // Cross-source collisions: same user, same sourceId string as a TRANSACTION, different source.
        String shared = "u" + userId + "-x" + sequence;
        long at = sequence * 7L + 2;
        items.add(new ProjectionItem(userId, SourceKind.TRANSACTION, shared, start.plusSeconds(at),
                P11[0], P11[1], "u" + userId + "-sx", "u" + userId + "-sx-p0", TxTypes.PLAY, amount, "EUR", null, null, detail));
        for (SourceKind kind : colliding) {
            at += 3;
            boolean sessioned = kind == SourceKind.CLAIMABLE_PRIZE; // prizes carry a session; payments/adjustments do not
            items.add(new ProjectionItem(userId, kind, shared, start.plusSeconds(at),
                    sessioned ? P11[0] : null,
                    sessioned ? P11[1] : null,
                    sessioned ? "u" + userId + "-sprize" : null,
                    sessioned ? "u" + userId + "-sprize-p0" : null,
                    TxTypes.NEUTRAL, amount,
                    sessioned ? joiningCurrency : "EUR", null, null, detail));
        }

        return items;
    }

    private EventGenerators() {}
}
