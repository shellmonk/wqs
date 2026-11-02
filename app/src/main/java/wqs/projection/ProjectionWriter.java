package wqs.projection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;
import wqs.ingest.stream.StreamOffsetStore;

/**
 * The single write choke point. Nothing else in WQS holds a write connection (spec section 6).
 *
 * Phase 2 performs steps 1-3 and 9 of the projection transaction described in spec 6.2.
 * Phase 3 adds cancellation, rollups, the high-level index and counter deltas between them.
 */
@Component
public class ProjectionWriter {

    private final ItemInserter itemInserter;
    private final StreamOffsetStore offsetStore;

    public ProjectionWriter(ItemInserter itemInserter, StreamOffsetStore offsetStore) {
        this.itemInserter = itemInserter;
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
            if (itemInserter.insertIfAbsent(item)) {
                projected++;
            } else {
                duplicates++;
            }
        }

        if (batch.committedOffset() != null) {
            offsetStore.write(batch.stream(), batch.committedOffset());
        }

        return new WriteResult(projected, duplicates);
    }
}
