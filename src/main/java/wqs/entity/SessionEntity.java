package wqs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code "wqPrjSession"}: one session's totals. Kept up to date by the native upserts in
 * {@link wqs.repository.SessionRepository}; read through JPA.
 */
@Entity
@Table(name = "wqPrjSession")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    private short ownerType;
    private long userId;
    private String batchId;
    private Instant startedAt;
    private Instant endedAt;
    private String productId;
    private String productType;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;
    private int playCount;
    private int playCountExclCancelled;
    private int txCount;
    private BigDecimal totalPlays;
    private BigDecimal totalWins;
    private BigDecimal net;
    private int txCountExclCancelled;
    private BigDecimal totalPlaysExclCancelled;
    private BigDecimal totalWinsExclCancelled;
    private BigDecimal netExclCancelled;
}
