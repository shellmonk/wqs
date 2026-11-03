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
import wqs.domain.ProjectionTransaction;

/**
 * Inserts a transaction once into "wqPrjTransaction", gated on the global locator. Which endpoint
 * sees it is decided by the per-endpoint SQL views over that table, never here.
 *
 * The locator insert IS the idempotency gate: ON CONFLICT DO NOTHING affecting zero rows means
 * the event has already been applied, so no source needs its own dedupe logic (spec 5.4).
 */
@Component
public class TransactionInserter {

    private static final String NEXT_TRANSACTION_ID = """
            select nextval('"wqTransactionIdSeq"')
            """;
    private static final String GATE = """
            insert into "wqPrjTransactionLocator" ("source", "sourceId", "userId", "occurredAt", "transactionId", "versionAt")
            values (?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """;
    private static final String GATE_LOCATOR_ONLY = """
            insert into "wqPrjTransactionLocator" ("source", "sourceId", "userId", "occurredAt", "transactionId", "zeroAmount", "cancels")
            values (?, ?, ?, ?, nextval('"wqTransactionIdSeq"'), true, ?)
            on conflict do nothing
            """;
    private static final String INSERT_TRANSACTION = """
            insert into "wqPrjTransaction" ("userId", "transactionId", "source", "sourceId", "occurredAt", "productId",
                    "productType", "batchId", "subBatchId", "type", "amount", "currency", "balanceAfter",
                    "cancels", "detail")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;

    private final JdbcClient jdbc;

    public TransactionInserter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The gate and view inserts must commit or roll back together, so callers must supply the
     * transaction (ProjectionWriter.apply does); calling without one fails fast.
     *
     * @return true if the transaction was newly inserted, false if it was already applied.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(ProjectionTransaction transaction) {
        return insertIfAbsent(transaction, null);
    }

    /**
     * As {@link #insertIfAbsent(ProjectionTransaction)}, recording {@code versionAt} -- a polled row's
     * version (spec 4, phase 6 D6) -- in the locator; null for an immutable stream transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(ProjectionTransaction transaction, java.time.Instant versionAt) {
        long transactionId = jdbc.sql(NEXT_TRANSACTION_ID).query(Long.class).single();
        OffsetDateTime occurredAt = transaction.occurredAt().atOffset(ZoneOffset.UTC);

        int gated = jdbc.sql(GATE)
                .params((short) transaction.source().code(), transaction.sourceId(), transaction.userId(),
                        timestamptz(occurredAt), transactionId, timestamptz(versionAt))
                .update();

        if (gated == 0) {
            return false;
        }

        jdbc.sql(INSERT_TRANSACTION)
                .params(transaction.userId(), transactionId, (short) transaction.source().code(), transaction.sourceId(),
                        timestamptz(occurredAt), text(transaction.productId()), text(transaction.productType()),
                        text(transaction.batchId()), text(transaction.subBatchId()), text(transaction.type()), transaction.amount(),
                        text(transaction.currency()), numeric(transaction.balanceAfter()), text(transaction.cancels()),
                        text(transaction.detailJson()))
                .update();
        return true;
    }

    /**
     * Records a zero-amount transaction in the locator ONLY (spec 4.1, plan D8): it stays idempotent and a
     * cancel referencing it resolves, but it is written to no transaction row. A zero-amount cancel keeps its
     * target in "cancels" here, having no transaction row to carry it.
     *
     * @return true if newly recorded, false if already applied.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordLocatorOnly(ProjectionTransaction transaction) {
        if (transaction.amount().signum() != 0) {
            throw new IllegalArgumentException("only a zero-amount transaction is locator-only: " + transaction.sourceId());
        }
        return jdbc.sql(GATE_LOCATOR_ONLY)
                .params((short) transaction.source().code(), transaction.sourceId(), transaction.userId(),
                        timestamptz(transaction.occurredAt()), text(transaction.cancels()))
                .update() == 1;
    }
}
