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

    /** @return true if the item was newly inserted, false if it was already applied. */
    public boolean insertIfAbsent(ProjectionItem item) {
        long itemId = dsl.nextval(WQ_ITEM_ID_SEQ);
        OffsetDateTime occurredAt = item.occurredAt().atOffset(ZoneOffset.UTC);

        int gated = dsl.insertInto(WQ_ITEM_LOCATOR)
                .set(WQ_ITEM_LOCATOR.source, (short) item.source().code())
                .set(WQ_ITEM_LOCATOR.sourceId, item.sourceId())
                .set(WQ_ITEM_LOCATOR.userId, item.userId())
                .set(WQ_ITEM_LOCATOR.occurredAt, occurredAt)
                .set(WQ_ITEM_LOCATOR.itemId, itemId)
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

    private void insertInto(ViewKind view, ProjectionItem item, long itemId, OffsetDateTime occurredAt) {
        switch (view) {
            case WEB -> dsl.insertInto(WQ_WEB_ITEM)
                    .set(WQ_WEB_ITEM.userId, item.userId())
                    .set(WQ_WEB_ITEM.itemId, itemId)
                    .set(WQ_WEB_ITEM.source, (short) item.source().code())
                    .set(WQ_WEB_ITEM.sourceId, item.sourceId())
                    .set(WQ_WEB_ITEM.occurredAt, occurredAt)
                    .set(WQ_WEB_ITEM.productId, item.productId())
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
