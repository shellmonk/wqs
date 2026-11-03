package wqs.projection;

import static wqs.db.generated.Sequences.WQ_ITEM_ID_SEQ;
import static wqs.db.generated.Tables.WQ_CC_ITEM;
import static wqs.db.generated.Tables.WQ_ITEM_LOCATOR;
import static wqs.db.generated.Tables.WQ_WEB_ITEM;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;

/**
 * Inserts an item into the view tables its source feeds, gated on the global locator.
 *
 * The locator insert IS the idempotency gate: ON CONFLICT DO NOTHING affecting zero rows means
 * the event has already been applied, so no source needs its own dedupe logic (spec 5.4).
 */
@Component
public class ItemInserter {

    private final DSLContext dsl;

    public ItemInserter(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * The gate and view inserts must commit or roll back together, so callers must supply the
     * transaction (ProjectionWriter.apply does); calling without one fails fast.
     *
     * @return true if the item was newly inserted, false if it was already applied.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(ProjectionItem item) {
        return insertIfAbsent(item, null);
    }

    /**
     * As {@link #insertIfAbsent(ProjectionItem)}, recording {@code versionAt} -- a polled row's
     * version (spec 4, phase 6 D6) -- in the locator; null for an immutable stream item.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(ProjectionItem item, java.time.Instant versionAt) {
        long itemId = dsl.nextval(WQ_ITEM_ID_SEQ);
        OffsetDateTime occurredAt = item.occurredAt().atOffset(ZoneOffset.UTC);

        int gated = dsl.insertInto(WQ_ITEM_LOCATOR)
                .set(WQ_ITEM_LOCATOR.source, (short) item.source().code())
                .set(WQ_ITEM_LOCATOR.sourceId, item.sourceId())
                .set(WQ_ITEM_LOCATOR.userId, item.userId())
                .set(WQ_ITEM_LOCATOR.occurredAt, occurredAt)
                .set(WQ_ITEM_LOCATOR.itemId, itemId)
                .set(WQ_ITEM_LOCATOR.versionAt, versionAt == null ? null : versionAt.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();

        if (gated == 0) {
            return false;
        }

        for (ViewKind view : item.source().views()) {
            insertInto(view, item, itemId, occurredAt);
        }
        return true;
    }

    /**
     * Records a zero-amount item in the locator ONLY (spec 4.1, plan D8): it stays idempotent and a
     * cancel referencing it resolves, but it is written to no view. A zero-amount cancel keeps its
     * target in "cancels" here, having no item row to carry it.
     *
     * @return true if newly recorded, false if already applied.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordLocatorOnly(ProjectionItem item) {
        if (item.amount().signum() != 0) {
            throw new IllegalArgumentException("only a zero-amount item is locator-only: " + item.sourceId());
        }
        return dsl.insertInto(WQ_ITEM_LOCATOR)
                .set(WQ_ITEM_LOCATOR.source, (short) item.source().code())
                .set(WQ_ITEM_LOCATOR.sourceId, item.sourceId())
                .set(WQ_ITEM_LOCATOR.userId, item.userId())
                .set(WQ_ITEM_LOCATOR.occurredAt, item.occurredAt().atOffset(ZoneOffset.UTC))
                .set(WQ_ITEM_LOCATOR.itemId, dsl.nextval(WQ_ITEM_ID_SEQ))
                .set(WQ_ITEM_LOCATOR.zeroAmount, true)
                .set(WQ_ITEM_LOCATOR.cancels, item.cancels())
                .onConflictDoNothing()
                .execute() == 1;
    }

    private void insertInto(ViewKind view, ProjectionItem item, long itemId, OffsetDateTime occurredAt) {
        switch (view) {
            case WEB -> dsl.insertInto(WQ_WEB_ITEM)
                    .set(WQ_WEB_ITEM.userId, item.userId())
                    .set(WQ_WEB_ITEM.itemId, itemId)
                    .set(WQ_WEB_ITEM.source, (short) item.source().code())
                    .set(WQ_WEB_ITEM.sourceId, item.sourceId())
                    .set(WQ_WEB_ITEM.occurredAt, occurredAt)
                    .set(WQ_WEB_ITEM.productId, item.productId())
                    .set(WQ_WEB_ITEM.productType, item.productType())
                    .set(WQ_WEB_ITEM.batchId, item.batchId())
                    .set(WQ_WEB_ITEM.subBatchId, item.subBatchId())
                    .set(WQ_WEB_ITEM.type, item.type())
                    .set(WQ_WEB_ITEM.amount, item.amount())
                    .set(WQ_WEB_ITEM.currency, item.currency())
                    .set(WQ_WEB_ITEM.balanceAfter, item.balanceAfter())
                    .set(WQ_WEB_ITEM.cancels, item.cancels())
                    .set(WQ_WEB_ITEM.detail, detail(item))
                    .execute();
            case CC -> dsl.insertInto(WQ_CC_ITEM)
                    .set(WQ_CC_ITEM.userId, item.userId())
                    .set(WQ_CC_ITEM.itemId, itemId)
                    .set(WQ_CC_ITEM.source, (short) item.source().code())
                    .set(WQ_CC_ITEM.sourceId, item.sourceId())
                    .set(WQ_CC_ITEM.occurredAt, occurredAt)
                    .set(WQ_CC_ITEM.productId, item.productId())
                    .set(WQ_CC_ITEM.productType, item.productType())
                    .set(WQ_CC_ITEM.batchId, item.batchId())
                    .set(WQ_CC_ITEM.subBatchId, item.subBatchId())
                    .set(WQ_CC_ITEM.type, item.type())
                    .set(WQ_CC_ITEM.amount, item.amount())
                    .set(WQ_CC_ITEM.currency, item.currency())
                    .set(WQ_CC_ITEM.balanceAfter, item.balanceAfter())
                    .set(WQ_CC_ITEM.cancels, item.cancels())
                    .set(WQ_CC_ITEM.detail, detail(item))
                    .execute();
        }
    }

    private static JSONB detail(ProjectionItem item) {
        return item.detailJson() == null ? null : JSONB.valueOf(item.detailJson());
    }
}
