package wqs.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The writer-side delta meters (phase 6 plan D10), pre-registered so a dashboard sees 0 rather than
 * no series: {@code wqs.delta.rows{outcome}} for every outcome and {@code wqs.delta.stale}. Every
 * row is stored, non-player owners included, so nothing is skipped.
 *
 * <p>Recorded after the batch's transaction commits: a rolled-back batch is retried, and counting
 * it twice would inflate every series.
 */
@Component
public class DeltaMetrics {

    public static final String ROWS = "wqs.delta.rows";
    public static final String STALE = "wqs.delta.stale";

    enum Outcome {
        INSERTED("inserted"), UPDATED("updated"), DUPLICATE("duplicate"), STALE("stale"),
        DEAD_LETTERED("dead-lettered");

        final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final Map<Outcome, Counter> rows = new EnumMap<>(Outcome.class);
    private final Counter stale;

    public DeltaMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            rows.put(outcome, Counter.builder(ROWS)
                    .tag("outcome", outcome.tag)
                    .description("Polled delta rows applied, by outcome")
                    .register(registry));
        }
        this.stale = Counter.builder(STALE)
                .description("Polled delta rows older than the stored version, ignored")
                .register(registry);
    }

    /** Records {@code result} once its transaction commits; at once if there is none. */
    void recordOnCommit(DeltaResult result) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record(result);
                }
            });
        } else {
            record(result);
        }
    }

    private void record(DeltaResult result) {
        rows.get(Outcome.INSERTED).increment(result.inserted());
        rows.get(Outcome.UPDATED).increment(result.updated());
        rows.get(Outcome.DUPLICATE).increment(result.duplicates());
        rows.get(Outcome.STALE).increment(result.stale());
        rows.get(Outcome.DEAD_LETTERED).increment(result.deadLettered());
        stale.increment(result.stale());
    }
}
