package wqs.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.NewTransaction;

/** {@code "wqPrjTransaction"}: every projected row, partitioned by month. */
@Repository
public class TransactionRepository {

    public static final String TABLE = "wqPrjTransaction";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TransactionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts the row; false when it already exists. A redelivered row hits the unique key
     * ("source", "sourceId", "occurredAt") and changes nothing. Its month's partition must exist.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(NewTransaction t) {
        return jdbc.sql("""
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
                .param("ownerType", t.ownerType())
                .param("userId", t.userId())
                .param("occurredAt", t.occurredAt().atOffset(ZoneOffset.UTC))
                .param("source", t.source().code())
                .param("sourceId", t.sourceId())
                .param("type", t.type())
                .param("amount", t.amount())
                .param("currency", t.currency())
                .param("balanceAfter", t.balanceAfter())
                .param("productId", t.productId())
                .param("productType", t.productType())
                .param("batchId", t.batchId())
                .param("subBatchId", t.subBatchId())
                .param("transactionReference", t.transactionReference())
                .param("detail", json(t))
                .update() == 1;
    }

    private String json(NewTransaction t) {
        try {
            return objectMapper.writeValueAsString(t.detail());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("detail is a flat map of strings and always serialises", e);
        }
    }
}
