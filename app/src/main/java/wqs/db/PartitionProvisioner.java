package wqs.db;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On-demand monthly partitions for whatever months a batch touches (spec 5.8, ruling R100).
 *
 * <p>The rollover job keeps a runway ahead of now, but events arrive for any month: the first
 * deployment replays the stream from offset 0 (O-9 backfill), and a producer may send an event
 * dated beyond the runway. Without a partition such an event can never be inserted and a good
 * event would be dead-lettered. The writer therefore calls {@link #ensureMonths} FIRST, before
 * any projection write.
 *
 * <p>The DDL runs in its OWN transaction (REQUIRES_NEW), never inside the projection
 * transaction: {@code CREATE TABLE ... PARTITION OF} takes an ACCESS EXCLUSIVE lock on the parent,
 * which must not be held for the rest of a batch. The projection transaction has not touched any
 * table when this runs, so suspending it cannot deadlock against it.
 *
 * <p>Months known to be present are cached so the steady state costs no round trip. The cache is
 * seeded lazily: a month not yet in it goes through {@link PartitionManager}, whose pg_inherits
 * check remains the truth, and joins the cache only after that transaction commits. If partitions
 * are ever dropped (retention, a test), call {@link #forgetKnownMonths()}.
 *
 * <p><b>Sanity window.</b> A month after now + {@code maxMonthsAhead} (12 by default) is a garbage
 * timestamp, not a partition to create: {@link IllegalArgumentException}, so the item takes the
 * normal retry / per-item / dead-letter path. There is no past floor.
 */
@Component
public class PartitionProvisioner {

    private final PartitionManager partitionManager;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final int maxMonthsAhead;
    private final Set<YearMonth> known = ConcurrentHashMap.newKeySet();

    public PartitionProvisioner(
            PartitionManager partitionManager,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${wqs.partitions.max-months-ahead:12}") int maxMonthsAhead) {
        this.partitionManager = partitionManager;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.maxMonthsAhead = maxMonthsAhead;
    }

    /**
     * Ensures every partitioned table has a partition for each of {@code months}.
     *
     * @throws IllegalArgumentException if any month is after now + maxMonthsAhead
     */
    public void ensureMonths(Set<YearMonth> months) {
        YearMonth limit = YearMonth.now(clock).plusMonths(maxMonthsAhead);
        for (YearMonth month : months) {
            if (month.isAfter(limit)) {
                throw new IllegalArgumentException("month " + month + " is more than " + maxMonthsAhead
                        + " months ahead (limit " + limit + "): treated as a garbage timestamp");
            }
        }
        List<YearMonth> missing = months.stream().filter(m -> !known.contains(m)).sorted().toList();
        if (missing.isEmpty()) {
            return;
        }
        requiresNew.executeWithoutResult(status -> {
            for (YearMonth month : missing) {
                partitionManager.ensurePartitionsAhead(month, 0);
            }
        });
        known.addAll(missing); // only once committed
    }

    /** Drops the cache, so the next call re-checks the catalog. For when partitions are dropped. */
    public void forgetKnownMonths() {
        known.clear();
    }
}
