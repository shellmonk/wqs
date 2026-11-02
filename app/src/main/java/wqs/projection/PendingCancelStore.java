package wqs.projection;

import static wqs.db.generated.Tables.WQ_PENDING_CANCEL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Row2;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import wqs.db.generated.tables.records.WqPendingCancelRecord;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;

/**
 * Parks cancels whose target has not arrived, and hands them back when it does (spec 6.3).
 *
 * A cancel targets an item of its own source, so a parked cancel waits for
 * (cancel.source, cancel.cancels) -- never for the sourceId alone, which different sources may
 * share. Every field of the cancel is stored, so a drained cancel is the cancel that arrived.
 *
 * {@link #park} and {@link #drainFor} run inside the projection transaction (the writer supplies
 * it); the gauges' {@link #depth} and {@link #oldestParkedAt} read committed state.
 */
@Component
public class PendingCancelStore {

    private final DSLContext dsl;

    public PendingCancelStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Parks a cancel; parking the same cancel again is a no-op. */
    public void park(ProjectionItem cancel) {
        if (!cancel.isCancellation()) {
            throw new IllegalArgumentException("not a cancellation: " + cancel.sourceId());
        }
        dsl.insertInto(WQ_PENDING_CANCEL)
                .set(WQ_PENDING_CANCEL.source, (short) cancel.source().code())
                .set(WQ_PENDING_CANCEL.cancelSourceId, cancel.sourceId())
                .set(WQ_PENDING_CANCEL.cancelsSourceId, cancel.cancels())
                .set(WQ_PENDING_CANCEL.userId, cancel.userId())
                .set(WQ_PENDING_CANCEL.occurredAt, cancel.occurredAt().atOffset(ZoneOffset.UTC))
                .set(WQ_PENDING_CANCEL.productId, cancel.productId())
                .set(WQ_PENDING_CANCEL.batchId, cancel.batchId())
                .set(WQ_PENDING_CANCEL.subBatchId, cancel.subBatchId())
                .set(WQ_PENDING_CANCEL.type, cancel.type())
                .set(WQ_PENDING_CANCEL.amount, cancel.amount())
                .set(WQ_PENDING_CANCEL.currency, cancel.currency())
                .set(WQ_PENDING_CANCEL.balanceAfter, cancel.balanceAfter())
                .set(WQ_PENDING_CANCEL.detail, cancel.detailJson())
                .onConflictDoNothing()
                .execute();
    }

    /**
     * Removes and returns the parked cancels targeting any of {@code targets}, ordered by
     * (source, cancel sourceId) so the order they are applied in does not depend on storage.
     */
    public List<ProjectionItem> drainFor(Set<ItemRef> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        List<Row2<Short, String>> keys = targets.stream()
                .map(t -> DSL.row((short) t.source().code(), t.sourceId()))
                .toList();
        return dsl.deleteFrom(WQ_PENDING_CANCEL)
                .where(DSL.row(WQ_PENDING_CANCEL.source, WQ_PENDING_CANCEL.cancelsSourceId)
                        .in(keys))
                .returning()
                .fetch()
                .stream()
                .sorted(Comparator.comparing(WqPendingCancelRecord::getSource)
                        .thenComparing(WqPendingCancelRecord::getCancelSourceId))
                .map(PendingCancelStore::toItem)
                .toList();
    }

    public int depth() {
        return dsl.fetchCount(WQ_PENDING_CANCEL);
    }

    public Optional<Instant> oldestParkedAt() {
        return Optional.ofNullable(dsl.select(DSL.min(WQ_PENDING_CANCEL.parkedAt))
                        .from(WQ_PENDING_CANCEL)
                        .fetchOne(0, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }

    private static ProjectionItem toItem(WqPendingCancelRecord r) {
        return new ProjectionItem(
                r.getUserId(),
                SourceKind.ofCode(r.getSource()),
                r.getCancelSourceId(),
                r.getOccurredAt().toInstant(),
                r.getProductId(),
                r.getBatchId(),
                r.getSubBatchId(),
                r.getType(),
                r.getAmount(),
                r.getCurrency(),
                r.getBalanceAfter(),
                r.getCancelsSourceId(),
                r.getDetail());
    }
}
