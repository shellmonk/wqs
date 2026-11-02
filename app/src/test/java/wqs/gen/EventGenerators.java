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

    /** All generated timestamps sit inside one month so a single partition suffices. */
    public static final Instant WINDOW_START = Instant.parse("2034-03-01T00:00:00Z");
    public static final Instant WINDOW_END = Instant.parse("2034-03-31T23:00:00Z");

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
        long spanSeconds = ChronoUnit.SECONDS.between(WINDOW_START, WINDOW_END);
        return Arbitraries.longs().between(0, spanSeconds).map(WINDOW_START::plusSeconds);
    }

    private static Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(-50_000, 50_000)
                .map(cents -> BigDecimal.valueOf(cents, 4));
    }

    /**
     * A sequence of items clustered into sessions: each session gets a batchId, a product, and
     * between 1 and 6 plays, each play 1 to 3 transactions.
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

    private static List<ProjectionItem> buildSequence(
            long userId, int sessions, int plays, int txPerPlay, Instant start, BigDecimal amount,
            java.util.Set<SourceKind> colliding, String detail) {
        List<ProjectionItem> items = new ArrayList<>();
        int sequence = 0;

        for (int s = 0; s < sessions; s++) {
            String batchId = "u" + userId + "-s" + s;
            for (int p = 0; p < plays; p++) {
                String subBatchId = batchId + "-p" + p;
                for (int t = 0; t < txPerPlay; t++) {
                    items.add(new ProjectionItem(
                            userId,
                            SourceKind.TRANSACTION,
                            batchId + "-t" + (sequence++),
                            start.plusSeconds(sequence * 7L),
                            11,
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

        // A /cc-only item joining the first session, a day before it: whichever of this and the
        // session's TRANSACTIONs arrives first decides which view's page-index row is created
        // first and which is materialised later, and when it arrives last it re-anchors the
        // session (and so moves its page-index rows and HIGH_LEVEL counters) across a UTC day.
        Instant dayBefore = start.minus(1, ChronoUnit.DAYS);
        items.add(new ProjectionItem(
                userId, SourceKind.MANUAL_ADJUSTMENT, "u" + userId + "-adj" + sequence,
                dayBefore.isBefore(WINDOW_START) ? WINDOW_START : dayBefore, 11,
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
