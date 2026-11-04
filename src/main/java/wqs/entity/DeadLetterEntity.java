package wqs.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row of {@code "wqDeadLetter"}: input that could not be projected. Never holds a raw message:
 * an unreadable one is identified by offset and {@code bodySha256}, a rejected one keeps only its
 * decoded, allowlisted fields in {@code payload}.
 */
@Entity
@Table(name = "wqDeadLetter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long deadLetterId;

    private String feed;
    private Long streamOffset;
    private String sourceId;
    private String reason;
    private String error;
    private String bodySha256;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;
    private Instant failedAt;

    public static DeadLetterEntity unreadable(String feed, long offset, String bodySha256, String reason,
                                              String error, Instant failedAt) {
        DeadLetterEntity d = new DeadLetterEntity();
        d.feed = feed;
        d.streamOffset = offset;
        d.bodySha256 = bodySha256;
        d.reason = reason;
        d.error = error;
        d.failedAt = failedAt;
        return d;
    }

    /** {@code payload} must hold only allowlisted fields. */
    public static DeadLetterEntity rejected(String feed, long offset, String sourceId, String reason, String error,
                                            Map<String, Object> payload, Instant failedAt) {
        DeadLetterEntity d = new DeadLetterEntity();
        d.feed = feed;
        d.streamOffset = offset;
        d.sourceId = sourceId;
        d.reason = reason;
        d.error = error;
        d.payload = payload;
        d.failedAt = failedAt;
        return d;
    }
}
