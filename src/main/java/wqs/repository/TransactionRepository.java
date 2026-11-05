package wqs.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.dto.IncomingTransaction;
import wqs.entity.TransactionEntity;
import wqs.entity.TransactionKey;

/** {@code wqPrjTransactions}: every projected row, partitioned by month. */
public interface TransactionRepository
        extends JpaRepository<TransactionEntity, TransactionKey>, JpaSpecificationExecutor<TransactionEntity> {

    String TABLE = "wqPrjTransactions";

    /**
     * Inserts a row; returns 0 when it already exists. A redelivered row hits the unique key
     * ("source", "sourceId", "created") and changes nothing. Native because JPA has no
     * "insert unless present": its save() would throw and mark the transaction rollback-only.
     * The row's month partition must exist.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into wqPrjTransactions (
                ownerId, created, transactionId, source, sourceId, accountType,
                accountNumber, accountId, batchId, subBatchId, batchEnded, transactionType,
                transactionSubtype, productId, productType, currency, amount, totalPostBalance,
                reservedAmount, nonWithdrawableAmount, monetary, description, fromDescription, toDescription,
                paymentId, claimId, extTrxId, extSystemId, transactionReference, channel,
                issued)
            values (
                :#{#t.ownerId()}, :#{#t.created()}, nextval('wqTransactionIdSeq'), :#{#t.source().code()},
                :#{#t.sourceId()}, :#{#t.accountType()}, :#{#t.accountNumber()}, :#{#t.accountId()},
                :#{#t.batchId()}, :#{#t.subBatchId()}, :#{#t.batchEnded()}, :#{#t.transactionType()},
                :#{#t.transactionSubtype()}, :#{#t.productId()}, :#{#t.productType()}, :#{#t.currency()},
                :#{#t.amount()}, :#{#t.totalPostBalance()}, :#{#t.reservedAmount()}, :#{#t.nonWithdrawableAmount()},
                :#{#t.monetary()}, :#{#t.description()}, :#{#t.fromDescription()}, :#{#t.toDescription()},
                :#{#t.paymentId()}, :#{#t.claimId()}, :#{#t.extTrxId()}, :#{#t.extSystemId()},
                :#{#t.transactionReference()}, :#{#t.channel()}, :#{#t.issued()})
            on conflict (source, sourceId, created) do nothing
            """)
    int insertIfAbsent(@Param("t") IncomingTransaction t);

    /**
     * The visible rows of the given sessions. {@code from}/{@code to} span the sessions' start and
     * end, so only the partitions holding them are read.
     */
    @Query("""
            select t from TransactionEntity t
            where t.ownerId = :ownerId and t.batchId in :batchIds
              and t.created >= :from and t.created <= :to
              and t.source in :sources and t.zeroAmount = false
              and (:includeCancelled = true or t.cancelled = false)
            order by t.created desc, t.transactionId desc
            """)
    List<TransactionEntity> findSessionRows(@Param("ownerId") String ownerId,
                                            @Param("batchIds") Collection<String> batchIds,
                                            @Param("from") Instant from, @Param("to") Instant to,
                                            @Param("sources") Collection<Short> sources,
                                            @Param("includeCancelled") boolean includeCancelled);

    /**
     * Rows by id. {@code from}/{@code to} (inclusive) must span the rows' own times: the primary key
     * leads with ("ownerId", "created"), so a tight span reads only those rows, not the owner's history.
     */
    @Query("""
            select t from TransactionEntity t
            where t.ownerId = :ownerId and t.transactionId in :ids and t.created >= :from and t.created <= :to
            """)
    List<TransactionEntity> findByIds(@Param("ownerId") String ownerId, @Param("ids") Collection<Long> ids,
                                      @Param("from") Instant from, @Param("to") Instant to);
}
