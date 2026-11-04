package wqs.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.IngestResult;
import wqs.dto.StreamBatchItem;
import wqs.dto.StreamBatchResult;
import wqs.dto.StreamMessage;
import wqs.entity.DeadLetterEntity;
import wqs.entity.IngestCursorEntity;
import wqs.exception.BatchItemRejectedException;
import wqs.exception.RejectedException;
import wqs.mapper.TransactionMapper;
import wqs.repository.DeadLetterRepository;
import wqs.repository.IngestCursorRepository;
import wqs.repository.TransactionRepository;
import wqs.util.Hashes;

/**
 * The stream's transactions. Each public method is ONE database transaction that also stores the
 * message's offset, so a message's effects and its position commit or roll back together.
 */
@Service
public class StreamIngestService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ProjectionWriter writer;
    private final PartitionService partitions;
    private final TransactionMapper mapper;
    private final IngestCursorRepository cursors;
    private final DeadLetterRepository deadLetters;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StreamIngestService(ProjectionWriter writer, PartitionService partitions, TransactionMapper mapper,
                               IngestCursorRepository cursors, DeadLetterRepository deadLetters, ObjectMapper objectMapper,
                               Clock clock) {
        this.writer = writer;
        this.partitions = partitions;
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

    /**
     * Creates the partitions a batch will write to. Call it BEFORE {@link #ingestBatch}, outside any
     * transaction: once the batch has written to the table, creating a partition would wait for the
     * batch's own lock. Garbage months are skipped here; the batch rejects them.
     */
    public void preparePartitions(List<StreamBatchItem> items) {
        items.stream()
                .filter(StreamBatchItem.Readable.class::isInstance)
                .map(i -> ((StreamBatchItem.Readable) i).message().created())
                .map(at -> java.time.YearMonth.from(at.atOffset(java.time.ZoneOffset.UTC)))
                .distinct()
                .forEach(month -> {
                    try {
                        partitions.ensure(TransactionRepository.TABLE, month.atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
                    } catch (RejectedException garbage) {
                        // rejected again, per message, inside the batch
                    }
                });
    }

    /**
     * Projects a batch of messages in ONE transaction and stores the last offset. The batch's
     * partitions must already exist ({@link #preparePartitions}). A rejected message
     * throws {@link BatchItemRejectedException} naming its offset, and the whole batch rolls back;
     * the caller retries it with that message as {@link StreamBatchItem.Rejected}.
     */
    @Transactional
    public StreamBatchResult ingestBatch(String feed, List<StreamBatchItem> items) {
        int stored = 0, alreadyStored = 0, deadLettered = 0;
        for (StreamBatchItem item : items) {
            switch (item) {
                case StreamBatchItem.Readable r -> {
                    IngestResult result;
                    try {
                        result = writer.write(mapper.fromStream(r.message()));
                    } catch (RejectedException e) {
                        throw new BatchItemRejectedException(r.offset(), e);
                    }
                    if (result == IngestResult.STORED) {
                        stored++;
                    } else {
                        alreadyStored++;
                    }
                }
                case StreamBatchItem.Unreadable u -> {
                    deadLetters.save(DeadLetterEntity.unreadable(feed, u.offset(), Hashes.sha256(u.body()), "unreadable",
                            u.error(), clock.instant()));
                    deadLettered++;
                }
                case StreamBatchItem.Rejected f -> {
                    deadLetters.save(DeadLetterEntity.rejected(feed, f.offset(), f.message().id(), f.reason(), f.error(),
                            objectMapper.convertValue(f.message(), MAP), clock.instant()));
                    deadLettered++;
                }
            }
        }
        cursors.saveStreamOffset(feed, items.getLast().offset());
        return new StreamBatchResult(stored, alreadyStored, deadLettered);
    }

    /** Projects the message. A {@link wqs.exception.RejectedException} rolls everything back, offset included. */
    @Transactional
    public IngestResult ingestOne(String feed, long offset, StreamMessage message) {
        IngestResult result = writer.write(mapper.fromStream(message));
        cursors.saveStreamOffset(feed, offset);
        return result;
    }

    /** An unreadable message: identified by offset and body hash only, never by its content. */
    @Transactional
    public void recordUnreadable(String feed, long offset, byte[] body, String reason, String error) {
        deadLetters.save(DeadLetterEntity.unreadable(feed, offset, Hashes.sha256(body), reason, error, clock.instant()));
        cursors.saveStreamOffset(feed, offset);
    }

    /** A readable but rejected message: kept with its decoded, allowlisted fields. */
    @Transactional
    public void recordRejected(String feed, long offset, StreamMessage message, String reason, String error) {
        deadLetters.save(DeadLetterEntity.rejected(feed, offset, message.id(), reason, error,
                objectMapper.convertValue(message, MAP), clock.instant()));
        cursors.saveStreamOffset(feed, offset);
    }
}
