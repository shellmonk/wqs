package wqs.projection;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import wqs.db.PartitionProvisioner;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;

/**
 * The single write choke point. Nothing else in WQS holds a write connection (spec section 6).
 *
 * Implements the ordering of spec 6.2: for each item, the ingest rules of spec 4.1 (a zero-amount
 * item is recorded in the locator only; a currency-mismatched or unsessioned eInstant item is
 * dead-lettered alone), then gate and insert, then (for a cancellation)
 * apply or park it, then rollups, page index and counters; after the items, drain the cancels
 * parked for items this batch inserted; then the offset last -- all in one transaction, so
 * projection state and consumer position advance or roll back together.
 */
@Component
public class ProjectionWriter {

    private static final Logger log = LoggerFactory.getLogger(ProjectionWriter.class);

    private final ItemInserter itemInserter;
    private final RollupUpdater rollupUpdater;
    private final HighLevelUpdater highLevelUpdater;
    private final CounterUpdater counterUpdater;
    private final CancellationApplier cancellationApplier;
    private final PendingCancelStore pendingCancelStore;
    private final OffsetSink offsetStore;
    private final PartitionProvisioner partitionProvisioner;
    private final DrainedCancelApplier drainedCancelApplier;
    private final DrainedCancelDeadLetters drainedCancelDeadLetters;
    private final IngestRules ingestRules;
    private final RejectedItemDeadLetters rejectedItemDeadLetters;

    public ProjectionWriter(
            ItemInserter itemInserter,
            RollupUpdater rollupUpdater,
            HighLevelUpdater highLevelUpdater,
            CounterUpdater counterUpdater,
            CancellationApplier cancellationApplier,
            PendingCancelStore pendingCancelStore,
            OffsetSink offsetStore,
            PartitionProvisioner partitionProvisioner,
            DrainedCancelApplier drainedCancelApplier,
            DrainedCancelDeadLetters drainedCancelDeadLetters,
            IngestRules ingestRules,
            RejectedItemDeadLetters rejectedItemDeadLetters) {
        this.itemInserter = itemInserter;
        this.rollupUpdater = rollupUpdater;
        this.highLevelUpdater = highLevelUpdater;
        this.counterUpdater = counterUpdater;
        this.cancellationApplier = cancellationApplier;
        this.pendingCancelStore = pendingCancelStore;
        this.offsetStore = offsetStore;
        this.partitionProvisioner = partitionProvisioner;
        this.drainedCancelApplier = drainedCancelApplier;
        this.drainedCancelDeadLetters = drainedCancelDeadLetters;
        this.ingestRules = ingestRules;
        this.rejectedItemDeadLetters = rejectedItemDeadLetters;
    }

    /**
     * Applies the whole batch, or nothing. The offset is written in this same transaction, so a
     * failure leaves the consumer position untouched and the batch is retried from where it was.
     */
    @Transactional
    public WriteResult apply(ProjectionBatch batch) {
        // FIRST, before any projection write, and in its own transaction: no DDL ever runs inside
        // this one (spec 5.8, R100). The occurredAt months cover every partitioned row the batch
        // can write -- item rows, session/play re-anchor targets (MIN(occurredAt)), page-index
        // rows and "wqCountDay" periods. A month beyond the sanity window throws, which is poison.
        partitionProvisioner.ensureMonths(batch.items().stream()
                .map(item -> YearMonth.from(item.occurredAt().atZone(ZoneOffset.UTC)))
                .collect(Collectors.toSet()));

        int projected = 0;
        int duplicates = 0;
        int skipped = 0;
        int deadLettered = 0;
        Set<ItemRef> inserted = new LinkedHashSet<>();

        for (int i = 0; i < batch.items().size(); i++) {
            ProjectionItem item = batch.items().get(i);

            // Zero-amount (spec 4.1, D8): the locator row only, so it stays idempotent and a
            // cancel referencing it resolves; no view, rollup, page row or counter. A zero-amount
            // cancel still cancels its target -- the effect lives on the target's rows.
            if (item.amount().signum() == 0) {
                if (!itemInserter.recordLocatorOnly(item)) {
                    duplicates++;
                    continue;
                }
                skipped++;
                inserted.add(ItemRef.of(item));
                if (item.isCancellation()) {
                    cancellationApplier.apply(item);
                }
                continue;
            }

            // Rejected items (D6, D7) are dead-lettered alone and nothing of them is written, not
            // even the locator row; the rest of the batch applies. Checked before the gate, so a
            // re-delivered rejected item is rejected again rather than counted as a duplicate.
            String rejection = ingestRules.rejection(item);
            if (rejection != null) {
                log.warn("Dead-lettering item {} (source {}): {}", item.sourceId(), item.source(), rejection);
                rejectedItemDeadLetters.recordRejected(batch.stream(), batch.offsetOf(i), item, rejection);
                deadLettered++;
                continue;
            }

            // The gate makes everything below safe to do incrementally: a replayed event never
            // gets past it, so no aggregate, counter or cancellation is ever applied twice.
            if (!itemInserter.insertIfAbsent(item)) {
                duplicates++;
                continue;
            }
            projected++;
            inserted.add(ItemRef.of(item));
            if (item.isCancellation()) {
                // Resolves against items committed earlier or inserted earlier in this batch;
                // otherwise parks, to be drained below or by a later batch.
                cancellationApplier.apply(item);
            }
            project(item);
        }

        // Cancels that arrived before their original: the original is now projected (by this
        // batch), so they resolve. This is what makes ingestion order-independent (spec 6.3).
        // Each is applied in its own savepoint. One that fails is rolled back alone, stays removed
        // from "wqPendingCancel" and is dead-lettered ITSELF: failing the batch instead would make
        // the per-item fallback dead-letter the innocent original whose arrival drained it.
        for (ProjectionItem parked : pendingCancelStore.drainFor(inserted)) {
            try {
                drainedCancelApplier.apply(parked);
            } catch (RuntimeException e) {
                log.error("Drained cancel {} (cancels {}) failed; dead-lettering the cancel, not its original",
                        parked.sourceId(), parked.cancels(), e);
                drainedCancelDeadLetters.recordFailedDrainedCancel(batch.stream(), parked, e);
                deadLettered++;
            }
        }

        if (batch.committedOffset() != null) {
            offsetStore.write(batch.stream(), batch.committedOffset());
        }

        return new WriteResult(projected, duplicates, deadLettered, skipped);
    }

    /**
     * Rollups, page index and counters for one newly inserted item.
     *
     * A cancellation item is a row in the item tables like any other, so it is counted at the
     * ITEM page unit (P-2: counters equal live COUNTs); the rollup and page-index updaters return
     * nothing for it, so it creates no page unit. Its effect on its original is applied by
     * {@link CancellationApplier}.
     */
    private void project(ProjectionItem item) {
        RollupResult rollup = rollupUpdater.applyItem(item);
        HighLevelResult highLevel = highLevelUpdater.applyItem(item, rollup);

        counterUpdater.countItem(item);

        // HIGH_LEVEL counters mirror the page index per view, at the row's CURRENT key: the
        // session's (start, product type) for a session row, the item's own for an unsessioned one.
        boolean sessioned = rollup.sessionStart() != null;
        Instant at = sessioned ? rollup.sessionStart() : item.occurredAt();
        String productType = sessioned ? rollup.sessionProductType() : item.productType();

        for (ViewKind view : highLevel.inserted()) {
            counterUpdater.countHighLevel(item.userId(), view, productType, at);
        }
        for (ViewKind view : highLevel.moved()) {
            counterUpdater.moveHighLevel(
                    item.userId(), view,
                    highLevel.movedFromProductType(), highLevel.movedFrom(),
                    productType, at,
                    highLevel.movedVisible().get(view));
        }
        // After the move: a revived row is counted in cntAll already, at the current key.
        for (ViewKind view : highLevel.revived()) {
            counterUpdater.recountExclCancelled(
                    item.userId(), view, PageUnit.HIGH_LEVEL, productType, at);
        }
    }
}
