package wqs.repository;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.entity.TransactionEntity;
import wqs.entity.TransactionKey;

/** {@code "wqPrjTransaction"}: every projected row, partitioned by month. */
public interface TransactionRepository extends JpaRepository<TransactionEntity, TransactionKey> {

    String TABLE = "wqPrjTransaction";

    /**
     * Inserts a row; returns 0 when it already exists. A redelivered row hits the unique key
     * ("source", "sourceId", "occurredAt") and changes nothing. Native because JPA has no
     * "insert unless present": its save() would throw and mark the transaction rollback-only.
     * The row's month partition must exist.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
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
    int insertIfAbsent(@Param("ownerType") short ownerType, @Param("userId") long userId,
                       @Param("occurredAt") Instant occurredAt, @Param("source") short source,
                       @Param("sourceId") String sourceId, @Param("type") String type,
                       @Param("amount") BigDecimal amount, @Param("currency") String currency,
                       @Param("balanceAfter") BigDecimal balanceAfter, @Param("productId") String productId,
                       @Param("productType") String productType, @Param("batchId") String batchId,
                       @Param("subBatchId") String subBatchId,
                       @Param("transactionReference") String transactionReference,
                       @Param("detail") String detailJson);
}
