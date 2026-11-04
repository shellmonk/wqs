package wqs.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A row of {@code "wqIngestCursor"}: one feed's last processed position. */
@Entity
@Table(name = "wqIngestCursor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestCursorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long cursorId;

    private String feed;
    private Long streamOffset;
    private Instant updatedAt;
}
