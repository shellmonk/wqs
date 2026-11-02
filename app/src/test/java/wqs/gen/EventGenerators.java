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
     * Products vary so that a session's key (startedAt, LEAST(productId)) depends on arrival
     * order, which drives every product-changing path under permutation: even sessions use
     * product 11, odd sessions 13 with a product-less FIRST play (so an early null-product
     * arrival is later raised to 13 in place, or never seen as a change if it arrives last), and
     * session 0 is joined by a /cc-only adjustment a day earlier at product 7 (start AND product
     * move) and one after its last transaction at product 3 (product-only change, same start).
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
                        details())
                .as(EventGenerators::buildSequence);
    }

    /**
     * {@link #itemSequences()} with cancellations (spec 8.1 requires cancels on both sides of their
     * originals). Every cancel targets an item of its OWN source and user (spec 6.3, R86) -- any
     * source, so unsessioned payments, play-less /cc-only adjustments and the cross-source
     * sourceId collisions are cancelled too.
     *
     * <ul>
     *   <li>About a third of sessions have EVERY item cancelled, /cc-only ones included, so
     *       they end hidden in both views. Interleaved with their originals (and arriving in
     *       either order), the cancels hide a session's rows and a later live item revives them,
     *       repeatedly.</li>
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
        java.util.Set<String> fullyCancelled = new java.util.HashSet<>();
        for (ProjectionItem item : items) {
            if (item.batchId() != null && !fullyCancelled.contains(item.batchId())
                    && random.nextInt(3) == 0) {
                fullyCancelled.add(item.batchId());
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
                ProjectionItem cancel = cancelOf(item, "-cancel" + c, random.nextBoolean());
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

    private static ProjectionItem cancelOf(ProjectionItem item, String suffix, boolean grouped) {
        return new ProjectionItem(
                item.userId(), item.source(), item.sourceId() + suffix,
                item.occurredAt().plusSeconds(90), item.productId(),
                grouped ? item.batchId() : null, grouped ? item.subBatchId() : null,
                (short) 2, item.amount().negate(), item.currency(), null,
                item.sourceId(), null);
    }

    private static List<ProjectionItem> buildSequence(
            long userId, int sessions, int plays, int txPerPlay, Instant start, BigDecimal amount,
            java.util.Set<SourceKind> colliding, String detail) {
        List<ProjectionItem> items = new ArrayList<>();
        int sequence = 0;

        for (int s = 0; s < sessions; s++) {
            String batchId = "u" + userId + "-s" + s;
            Integer sessionProduct = s % 2 == 0 ? 11 : 13;
            for (int p = 0; p < plays; p++) {
                Integer product = s % 2 == 1 && p == 0 ? null : sessionProduct;
                String subBatchId = batchId + "-p" + p;
                for (int t = 0; t < txPerPlay; t++) {
                    items.add(new ProjectionItem(
                            userId,
                            SourceKind.TRANSACTION,
                            batchId + "-t" + (sequence++),
                            start.plusSeconds(sequence * 7L),
                            product,
                            batchId,
                            subBatchId,
                            (short) (t == 0 ? 1 : 2),
                            amount,
                            "EUR",
                            null,
                            null,
                            detail));
                }
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
                start.minus(1, ChronoUnit.DAYS), 7,
                "u" + userId + "-s0", null, (short) 9, amount, "EUR", null, null, null));
        items.add(new ProjectionItem(
                userId, SourceKind.MANUAL_ADJUSTMENT, "u" + userId + "-adjlate" + sequence,
                start.plusSeconds(sequence * 7L + 5), 3,
                "u" + userId + "-s0", null, (short) 9, amount, "EUR", null, null, null));

        // Unsessioned items: payments carry no batchId (spec 7.4).
        items.add(new ProjectionItem(
                userId, SourceKind.PAYMENT, "u" + userId + "-pay" + sequence,
                start.plusSeconds(sequence * 7L + 1), null, null, null,
                (short) 9, amount.abs(), "EUR", null, null, null));

        // Cross-source collisions: same user, same sourceId string as a TRANSACTION, different source.
        String shared = "u" + userId + "-x" + sequence;
        long at = sequence * 7L + 2;
        items.add(new ProjectionItem(userId, SourceKind.TRANSACTION, shared, start.plusSeconds(at),
                11, "u" + userId + "-sx", "u" + userId + "-sx-p0", (short) 1, amount, "EUR", null, null, detail));
        for (SourceKind kind : colliding) {
            at += 3;
            boolean sessioned = kind == SourceKind.CLAIMABLE_PRIZE; // prizes carry a session; payments/adjustments do not
            items.add(new ProjectionItem(userId, kind, shared, start.plusSeconds(at),
                    sessioned ? 11 : null,
                    sessioned ? "u" + userId + "-sprize" : null,
                    sessioned ? "u" + userId + "-sprize-p0" : null,
                    (short) (kind == SourceKind.PAYMENT ? 9 : 3), amount, "EUR", null, null, detail));
        }

        return items;
    }

    private EventGenerators() {}
}
