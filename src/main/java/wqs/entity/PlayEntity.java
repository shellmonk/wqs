package wqs.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code "wqPrjPlay"}: one play's totals. Kept up to date by the native upserts in
 * {@link wqs.repository.PlayRepository}; read through JPA.
 */
@Entity
@Table(name = "wqPrjPlay")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long playId;

    private long sessionId;
    private String ownerId;
    private String batchId;
    private String subBatchId;
    private Instant startedAt;
    private Instant endedAt;
    private String productId;
    private String productType;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;
    /** Over all of its rows. */
    @Embedded
    private Totals totals;

    /** Over its rows that are not cancelled. */
    @Embedded
    @AttributeOverride(name = "txCount", column = @Column(name = "txCountExclCancelled"))
    @AttributeOverride(name = "totalPlays", column = @Column(name = "totalPlaysExclCancelled"))
    @AttributeOverride(name = "totalWins", column = @Column(name = "totalWinsExclCancelled"))
    @AttributeOverride(name = "net", column = @Column(name = "netExclCancelled"))
    private Totals totalsExclCancelled;

    /** The totals a request asks for: over all rows, or over rows not cancelled. */
    public Totals totals(boolean excludeCancelled) {
        return excludeCancelled ? totalsExclCancelled : totals;
    }
}
