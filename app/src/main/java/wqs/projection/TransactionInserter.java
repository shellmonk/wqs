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
 * Inserts a transaction once into "wqPrjTransaction". Which endpoint sees it is decided at read
 * time by {@code wqs.read.ReadPolicy}, never here.
 *
 * <p>The insert IS the idempotency gate (plan D2): the table's unique key ("source", "sourceId",
 * "occurredAt") makes a redelivered event conflict, and ON CONFLICT DO NOTHING affecting zero rows
 * means the event has already been applied, so no source needs its own dedupe logic. There is no
 * locator: the key leads with ("source", "sourceId") and holds the partition key, so the probe is one
 * index lookup in one partition. Every transaction takes this path, zero-amount and non-player ones
 * included; "zeroAmount" is a generated column, never written.
 */
@Component
public class TransactionInserter {

    private static final String INSERT_TRANSACTION = """
            insert into "wqPrjTransaction" ("ownerType", "userId", "transactionId", "source", "sourceId", "occurredAt",
                    "productId", "productType", "batchId", "subBatchId", "type", "amount", "currency", "balanceAfter",
                    "cancels", "detail", "versionAt")
            values (?, ?, nextval('"wqTransactionIdSeq"'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
            on conflict ("source", "sourceId", "occurredAt") do nothing
            """;

    private final JdbcClient jdbc;

    public TransactionInserter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The insert must commit or roll back with everything the writer derives from it, so callers
     * must supply the transaction (ProjectionWriter.apply does); calling without one fails fast.
     *
     * @return true if the transaction was newly inserted, false if it was already applied.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(ProjectionTransaction transaction) {
        return insertIfAbsent(transaction, null);
    }

    /**
     * As {@link #insertIfAbsent(ProjectionTransaction)}, recording {@code versionAt} -- a polled row's
     * version (spec 4, phase 6 D6); null for an immutable stream transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(ProjectionTransaction transaction, java.time.Instant versionAt) {
        OffsetDateTime occurredAt = transaction.occurredAt().atOffset(ZoneOffset.UTC);
        int inserted = jdbc.sql(INSERT_TRANSACTION)
                .params(transaction.ownerType(), transaction.userId(),
                        (short) transaction.source().code(), transaction.sourceId(),
                        timestamptz(occurredAt), text(transaction.productId()), text(transaction.productType()),
                        text(transaction.batchId()), text(transaction.subBatchId()), text(transaction.type()), transaction.amount(),
                        text(transaction.currency()), numeric(transaction.balanceAfter()), text(transaction.cancels()),
                        text(transaction.detailJson()), timestamptz(versionAt))
                .update();
        return inserted == 1;
    }
}
