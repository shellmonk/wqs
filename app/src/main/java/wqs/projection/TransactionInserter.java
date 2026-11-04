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
 * the event has already been applied, so no source needs its own dedupe logic (spec 5.4). Every
 * transaction takes this path, zero-amount and non-player ones included; "zeroAmount" is a
 * generated column, never written.
 */
@Component
public class TransactionInserter {

    private static final String NEXT_TRANSACTION_ID = """
            select nextval('"wqTransactionIdSeq"')
            """;
    private static final String GATE = """
            insert into "wqPrjTransactionLocator" ("source", "sourceId", "ownerType", "userId", "occurredAt", "transactionId", "versionAt")
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """;
    private static final String INSERT_TRANSACTION = """
            insert into "wqPrjTransaction" ("ownerType", "userId", "transactionId", "source", "sourceId", "occurredAt", "productId",
                    "productType", "batchId", "subBatchId", "type", "amount", "currency", "balanceAfter",
                    "cancels", "detail")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
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
                .params((short) transaction.source().code(), transaction.sourceId(), transaction.ownerType(),
                        transaction.userId(), timestamptz(occurredAt), transactionId, timestamptz(versionAt))
                .update();

        if (gated == 0) {
            return false;
        }

        jdbc.sql(INSERT_TRANSACTION)
                .params(transaction.ownerType(), transaction.userId(), transactionId,
                        (short) transaction.source().code(), transaction.sourceId(),
                        timestamptz(occurredAt), text(transaction.productId()), text(transaction.productType()),
                        text(transaction.batchId()), text(transaction.subBatchId()), text(transaction.type()), transaction.amount(),
                        text(transaction.currency()), numeric(transaction.balanceAfter()), text(transaction.cancels()),
                        text(transaction.detailJson()))
                .update();
        return true;
    }
}
