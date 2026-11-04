package wqs.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.db.OutOfRangeException;
import wqs.db.Partitions;
import wqs.domain.Source;
import wqs.ingest.stream.StreamMessage;

/** Stores rows in {@code "wqPrjTransaction"}. Runs inside the caller's ingest transaction. */
@Component
public class TransactionWriter {

    public enum Outcome { INSERTED, DUPLICATE }

    static final String TABLE = "wqPrjTransaction";

    private final JdbcClient jdbc;
    private final Partitions partitions;
    private final ObjectMapper objectMapper;

    public TransactionWriter(JdbcClient jdbc, Partitions partitions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.partitions = partitions;
        this.objectMapper = objectMapper;
    }

    /**
     * A redelivered message hits the unique key ("source", "sourceId", "occurredAt") and is a duplicate.
     *
     * <p>{@link OutOfRangeException} is thrown before any write and leaves the caller's transaction
     * usable (noRollbackFor), so the caller can dead-letter the row and advance its offset in it.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OutOfRangeException.class)
    public Outcome write(StreamMessage m) {
        partitions.ensure(TABLE, m.created());
        int inserted = jdbc.sql("""
                insert into "wqPrjTransaction" (
                    "ownerType", "userId", "occurredAt", "transactionId", "source", "sourceId", "type",
                    "amount", "currency", "balanceAfter", "productId", "productType", "batchId", "subBatchId",
                    "transactionReference", "detail")
                values (
                    :ownerType, :userId, :occurredAt, nextval('"wqTransactionIdSeq"'), :source, :sourceId, :type,
                    :amount, :currency, :balanceAfter, :productId, :productType, :batchId, :subBatchId,
                    :transactionReference, cast(:detail as jsonb))
                on conflict ("source", "sourceId", "occurredAt") do nothing
                """)
                .param("ownerType", m.ownerType())
                .param("userId", m.userId())
                .param("occurredAt", m.created().atOffset(ZoneOffset.UTC))
                .param("source", Source.TRANSACTION.code())
                .param("sourceId", m.id())
                .param("type", m.type())
                .param("amount", m.amount())
                .param("currency", m.currency())
                .param("balanceAfter", m.totalPostBalance())
                .param("productId", m.productId())
                .param("productType", m.productType())
                .param("batchId", m.batchId())
                .param("subBatchId", m.subBatchId())
                .param("transactionReference", m.transactionReference())
                .param("detail", json(m))
                .update();
        return inserted == 1 ? Outcome.INSERTED : Outcome.DUPLICATE;
    }

    private String json(StreamMessage m) {
        try {
            return objectMapper.writeValueAsString(m.detail());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("detail is a flat map of strings and always serialises", e);
        }
    }
}
