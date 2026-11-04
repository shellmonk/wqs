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
 * A row of {@code "wqPrjSessions"}: one session's totals. Kept up to date by the native upserts in
 * {@link wqs.repository.SessionRepository}; read through JPA.
 */
@Entity
@Table(name = "wqPrjSessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    private String ownerId;
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

    /** How many plays have a row (that is not cancelled, when {@code excludeCancelled}). */
    public int playCount(boolean excludeCancelled) {
        return excludeCancelled ? playCountExclCancelled : playCount;
    }
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
