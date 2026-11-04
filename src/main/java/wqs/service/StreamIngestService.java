package wqs.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.IngestResult;
import wqs.dto.StreamMessage;
import wqs.entity.DeadLetterEntity;
import wqs.entity.IngestCursorEntity;
import wqs.mapper.TransactionMapper;
import wqs.repository.DeadLetterRepository;
import wqs.repository.IngestCursorRepository;
import wqs.util.Hashes;

/**
 * The stream's transactions. Each public method is ONE database transaction that also stores the
 * message's offset, so a message's effects and its position commit or roll back together.
 */
@Service
public class StreamIngestService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final TransactionIngestService ingest;
    private final TransactionMapper mapper;
    private final IngestCursorRepository cursors;
    private final DeadLetterRepository deadLetters;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StreamIngestService(TransactionIngestService ingest, TransactionMapper mapper, IngestCursorRepository cursors,
                               DeadLetterRepository deadLetters, ObjectMapper objectMapper, Clock clock) {
        this.ingest = ingest;
        this.mapper = mapper;
        this.cursors = cursors;
        this.deadLetters = deadLetters;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OptionalLong lastOffset(String feed) {
        return cursors.findByFeed(feed)
                .map(IngestCursorEntity::getStreamOffset)
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
    }

    /** Projects the message. A {@link wqs.exception.RejectedException} rolls everything back, offset included. */
    @Transactional
    public IngestResult ingest(String feed, long offset, StreamMessage message) {
        IngestResult result = ingest.ingest(mapper.fromStream(message));
        cursors.saveStreamOffset(feed, offset);
        return result;
    }

    /** An unreadable message: identified by offset and body hash only, never by its content. */
    @Transactional
    public void recordUnreadable(String feed, long offset, byte[] body, String reason, String error) {
        deadLetters.save(DeadLetterEntity.unreadable(feed, offset, Hashes.sha256(body), reason, error, clock.instant()));
        cursors.saveStreamOffset(feed, offset);
    }

    /** A readable but refused message: kept with its decoded, allowlisted fields. */
    @Transactional
    public void recordRejected(String feed, long offset, StreamMessage message, String reason, String error) {
        deadLetters.save(DeadLetterEntity.rejected(feed, offset, message.id(), reason, error,
                objectMapper.convertValue(message, MAP), clock.instant()));
        cursors.saveStreamOffset(feed, offset);
    }
}
