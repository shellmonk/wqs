package wqs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code "wqPrjTransactions"}. Read through JPA; written only by the native, duplicate-safe
 * insert in {@link wqs.repository.TransactionRepository}, so the entity is never persisted itself.
 */
@Entity
@Table(name = "wqPrjTransactions")
@IdClass(TransactionKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntity {

    @Id private String ownerId;
    @Id private Instant occurredAt;
    @Id private Long transactionId;

    private short source;
    private String sourceId;
    private String type;
    private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;
    private BigDecimal balanceAfter;
    private String productId;
    private String productType;
    private String batchId;
    private String subBatchId;
    private String transactionReference;
    private boolean cancelled;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> detail;
    private Instant ingestedAt;
    @Column(insertable = false, updatable = false)
    private boolean zeroAmount;
}
