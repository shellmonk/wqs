package wqs.projection;

import static wqs.projection.Binds.numeric;
import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private static final String NEXT_ITEM_ID = """
            select nextval('"wqItemIdSeq"')
            """;
    private static final String GATE = """
            insert into "wqItemLocator" ("source", "sourceId", "userId", "occurredAt", "itemId", "versionAt")
            values (?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """;
    private static final String GATE_LOCATOR_ONLY = """
            insert into "wqItemLocator" ("source", "sourceId", "userId", "occurredAt", "itemId", "zeroAmount", "cancels")
            values (?, ?, ?, ?, nextval('"wqItemIdSeq"'), true, ?)
            on conflict do nothing
            """;
    private static final String INSERT_ITEM = """
            insert into "%s" ("userId", "itemId", "source", "sourceId", "occurredAt", "productId",
                    "productType", "batchId", "subBatchId", "type", "amount", "currency", "balanceAfter",
                    "cancels", "detail")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;
    private static final String INSERT_WEB_ITEM = INSERT_ITEM.formatted("wqWebItem");
    private static final String INSERT_CC_ITEM = INSERT_ITEM.formatted("wqCcItem");

    private final JdbcClient jdbc;

    public ItemInserter(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        long itemId = jdbc.sql(NEXT_ITEM_ID).query(Long.class).single();
        OffsetDateTime occurredAt = item.occurredAt().atOffset(ZoneOffset.UTC);

        int gated = jdbc.sql(GATE)
                .params((short) item.source().code(), item.sourceId(), item.userId(),
                        timestamptz(occurredAt), itemId, timestamptz(versionAt))
                .update();

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
        return jdbc.sql(GATE_LOCATOR_ONLY)
                .params((short) item.source().code(), item.sourceId(), item.userId(),
                        timestamptz(item.occurredAt()), text(item.cancels()))
                .update() == 1;
    }

    private void insertInto(ViewKind view, ProjectionItem item, long itemId, OffsetDateTime occurredAt) {
        String sql = switch (view) {
            case WEB -> INSERT_WEB_ITEM;
            case CC -> INSERT_CC_ITEM;
        };
        jdbc.sql(sql)
                .params(item.userId(), itemId, (short) item.source().code(), item.sourceId(),
                        timestamptz(occurredAt), text(item.productId()), text(item.productType()),
                        text(item.batchId()), text(item.subBatchId()), item.type(), item.amount(),
                        text(item.currency()), numeric(item.balanceAfter()), text(item.cancels()),
                        text(item.detailJson()))
                .update();
    }
}
