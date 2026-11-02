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

    public static Arbitrary<SourceKind> sources() {
        return Arbitraries.of(
                SourceKind.TRANSACTION, SourceKind.TRANSACTION, SourceKind.TRANSACTION,
                SourceKind.CLAIMABLE_PRIZE, SourceKind.PAYMENT, SourceKind.MANUAL_ADJUSTMENT);
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
                        amounts())
                .as(EventGenerators::buildSequence);
    }

    private static List<ProjectionItem> buildSequence(
            long userId, int sessions, int plays, int txPerPlay, Instant start, BigDecimal amount) {
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
                            null));
                }
            }
        }

        // Unsessioned items: payments carry no batchId (spec 7.4).
        items.add(new ProjectionItem(
                userId, SourceKind.PAYMENT, "u" + userId + "-pay" + sequence,
                start.plusSeconds(sequence * 7L + 1), null, null, null,
                (short) 9, amount.abs(), "EUR", null, null, null));

        return items;
    }

    private EventGenerators() {}
}
