package wqs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code wqPrjTransactions}. Read through JPA; written only by the native, duplicate-safe
 * insert in {@link wqs.repository.TransactionRepository}, so the entity is never persisted itself.
 */
@Entity
@Table(name = "wqPrjTransactions")
@IdClass(TransactionKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntity {

    @Id private String ownerId;
    @Id private Instant created;
    @Id private Long transactionId;

    private short source;
    private String sourceId;
    @Column(insertable = false, updatable = false)
    private int userType;
    @Column(insertable = false, updatable = false)
    private String userId;
    private String accountType;
    private String accountNumber;
    private String accountId;
    private String batchId;
    private String subBatchId;
    private Boolean batchEnded;
    private String transactionType;
    private String transactionSubtype;
    private String productId;
    private String productType;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;
    private BigDecimal amount;
    private BigDecimal totalPostBalance;
    private BigDecimal reservedAmount;
    private BigDecimal nonWithdrawableAmount;
    private boolean monetary;
    private String description;
    private String fromDescription;
    private String toDescription;
    private Long paymentId;
    private Long claimId;
    private String extTrxId;
    private String extSystemId;
    private String transactionReference;
    private String channel;
    private Instant issued;
    private boolean cancelled;
    private Instant ingestedAt;
    @Column(insertable = false, updatable = false)
    private boolean zeroAmount;
}
