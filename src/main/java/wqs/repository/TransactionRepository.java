package wqs.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.entity.TransactionEntity;
import wqs.entity.TransactionKey;

/** {@code "wqPrjTransaction"}: every projected row, partitioned by month. */
public interface TransactionRepository
        extends JpaRepository<TransactionEntity, TransactionKey>, JpaSpecificationExecutor<TransactionEntity> {

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

    /**
     * The visible rows of the given sessions. {@code from}/{@code to} span the sessions' start and
     * end, so only the partitions holding them are read.
     */
    @Query("""
            select t from TransactionEntity t
            where t.userId = :userId and t.ownerType = :ownerType and t.batchId in :batchIds
              and t.occurredAt >= :from and t.occurredAt <= :to
              and t.source in :sources and t.zeroAmount = false
              and (:includeCancelled = true or t.cancelled = false)
            order by t.occurredAt desc, t.transactionId desc
            """)
    List<TransactionEntity> findSessionRows(@Param("userId") long userId, @Param("ownerType") short ownerType,
                                            @Param("batchIds") Collection<String> batchIds,
                                            @Param("from") Instant from, @Param("to") Instant to,
                                            @Param("sources") Collection<Short> sources,
                                            @Param("includeCancelled") boolean includeCancelled);

    /** Rows by id, bounded to the request's range so only its partitions are read. */
    @Query("""
            select t from TransactionEntity t
            where t.userId = :userId and t.transactionId in :ids and t.occurredAt >= :from and t.occurredAt < :to
            """)
    List<TransactionEntity> findByIds(@Param("userId") long userId, @Param("ids") Collection<Long> ids,
                                      @Param("from") Instant from, @Param("to") Instant to);
}
