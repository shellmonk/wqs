package wqs.projection;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;
import wqs.ingest.stream.StreamOffsetStore;

/**
 * The single write choke point. Nothing else in WQS holds a write connection (spec section 6).
 *
 * Implements the ordering of spec 6.2: for each item, gate and insert, then (for a cancellation)
 * apply or park it, then rollups, page index and counters; after the items, drain the cancels
 * parked for items this batch inserted; then the offset last -- all in one transaction, so
 * projection state and consumer position advance or roll back together.
 */
@Component
public class ProjectionWriter {

    private final ItemInserter itemInserter;
    private final RollupUpdater rollupUpdater;
    private final HighLevelUpdater highLevelUpdater;
    private final CounterUpdater counterUpdater;
    private final CancellationApplier cancellationApplier;
    private final PendingCancelStore pendingCancelStore;
    private final StreamOffsetStore offsetStore;

    public ProjectionWriter(
            ItemInserter itemInserter,
            RollupUpdater rollupUpdater,
            HighLevelUpdater highLevelUpdater,
            CounterUpdater counterUpdater,
            CancellationApplier cancellationApplier,
            PendingCancelStore pendingCancelStore,
            StreamOffsetStore offsetStore) {
        this.itemInserter = itemInserter;
        this.rollupUpdater = rollupUpdater;
        this.highLevelUpdater = highLevelUpdater;
        this.counterUpdater = counterUpdater;
        this.cancellationApplier = cancellationApplier;
        this.pendingCancelStore = pendingCancelStore;
        this.offsetStore = offsetStore;
    }

    /**
     * Applies the whole batch, or nothing. The offset is written in this same transaction, so a
     * failure leaves the consumer position untouched and the batch is retried from where it was.
     */
    @Transactional
    public WriteResult apply(ProjectionBatch batch) {
        int projected = 0;
        int duplicates = 0;
        Set<ItemRef> inserted = new LinkedHashSet<>();

        for (ProjectionItem item : batch.items()) {
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
        for (ProjectionItem parked : pendingCancelStore.drainFor(inserted)) {
            cancellationApplier.apply(parked);
        }

        if (batch.committedOffset() != null) {
            offsetStore.write(batch.stream(), batch.committedOffset());
        }

        return new WriteResult(projected, duplicates);
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
        // session's (start, product) for a session row, the item's own for an unsessioned one.
        boolean sessioned = rollup.sessionStart() != null;
        Instant at = sessioned ? rollup.sessionStart() : item.occurredAt();
        Integer productId = sessioned ? rollup.sessionProductId() : item.productId();

        for (ViewKind view : highLevel.inserted()) {
            counterUpdater.countHighLevel(item.userId(), view, productId, at);
        }
        for (ViewKind view : highLevel.moved()) {
            counterUpdater.moveHighLevel(
                    item.userId(), view,
                    highLevel.movedFromProductId(), highLevel.movedFrom(),
                    productId, at,
                    highLevel.movedVisible().get(view));
        }
        // After the move: a revived row is counted in cntAll already, at the current key.
        for (ViewKind view : highLevel.revived()) {
            counterUpdater.recountExclCancelled(
                    item.userId(), view, PageUnit.HIGH_LEVEL, productId, at);
        }
    }
}
