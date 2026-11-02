package wqs.projection;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;
import wqs.ingest.stream.StreamOffsetStore;

/**
 * The single write choke point. Nothing else in WQS holds a write connection (spec section 6).
 *
 * Implements the ordering of spec 6.2 for each item: gate and insert, rollups, page index,
 * counters; then the offset last -- all in one transaction, so projection state and consumer
 * position advance or roll back together. Cancellation effects (6.2 steps 4-5) are applied by a
 * later task.
 */
@Component
public class ProjectionWriter {

    private final ItemInserter itemInserter;
    private final RollupUpdater rollupUpdater;
    private final HighLevelUpdater highLevelUpdater;
    private final CounterUpdater counterUpdater;
    private final StreamOffsetStore offsetStore;

    public ProjectionWriter(
            ItemInserter itemInserter,
            RollupUpdater rollupUpdater,
            HighLevelUpdater highLevelUpdater,
            CounterUpdater counterUpdater,
            StreamOffsetStore offsetStore) {
        this.itemInserter = itemInserter;
        this.rollupUpdater = rollupUpdater;
        this.highLevelUpdater = highLevelUpdater;
        this.counterUpdater = counterUpdater;
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

        for (ProjectionItem item : batch.items()) {
            // The gate makes everything below safe to do incrementally: a replayed event never
            // gets past it, so no aggregate or counter is ever applied twice.
            if (!itemInserter.insertIfAbsent(item)) {
                duplicates++;
                continue;
            }
            projected++;
            project(item);
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
     * nothing for it, so it creates no page unit.
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
    }
}
