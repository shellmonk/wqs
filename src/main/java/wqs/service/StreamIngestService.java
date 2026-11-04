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
 * The stream's database transactions. Each public method that writes is ONE transaction that also
 * stores the stream offset, so a message's effects and its position commit or roll back together.
 * {@code feed} is the cursor's key: the stream's name.
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
     * batch's own lock (see {@link PartitionDdlService}).
     */
    public void preparePartitions(List<StreamBatchItem> items) {
        partitions.ensureMonthsOf(TransactionRepository.TABLE, items.stream()
                .filter(StreamBatchItem.Readable.class::isInstance)
                .map(item -> ((StreamBatchItem.Readable) item).message().created())
                .toList());
    }

    /**
     * Projects a batch of messages in ONE transaction and stores the last offset. The batch's
     * partitions must already exist ({@link #preparePartitions}). A rejected message throws
     * {@link BatchItemRejectedException} naming its offset, and the whole batch rolls back.
     */
    @Transactional
    public StreamBatchResult ingestBatch(String feed, List<StreamBatchItem> items) {
        int stored = 0, alreadyStored = 0, deadLettered = 0;
        for (StreamBatchItem item : items) {
            switch (item) {
                case StreamBatchItem.Readable readable -> {
                    if (write(readable) == IngestResult.STORED) {
                        stored++;
                    } else {
                        alreadyStored++;
                    }
                }
                case StreamBatchItem.Unreadable unreadable -> {
                    saveUnreadable(feed, unreadable.offset(), unreadable.body(), "unreadable", unreadable.error());
                    deadLettered++;
                }
                case StreamBatchItem.Rejected rejected -> {
                    saveRejected(feed, rejected.offset(), rejected.message(), rejected.reason(), rejected.error());
                    deadLettered++;
                }
            }
        }
        cursors.saveStreamOffset(feed, items.getLast().offset());
        return new StreamBatchResult(stored, alreadyStored, deadLettered);
    }

    /** Projects one message. A {@link RejectedException} rolls everything back, offset included. */
    @Transactional
    public IngestResult ingestOne(String feed, long offset, StreamMessage message) {
        IngestResult result = writer.write(mapper.fromStream(message));
        cursors.saveStreamOffset(feed, offset);
        return result;
    }

    /** Dead-letters an unreadable message (by offset and body hash only) and moves the offset past it. */
    @Transactional
    public void recordUnreadable(String feed, long offset, byte[] body, String reason, String error) {
        saveUnreadable(feed, offset, body, reason, error);
        cursors.saveStreamOffset(feed, offset);
    }

    /** Dead-letters a rejected message (with its allowlisted fields) and moves the offset past it. */
    @Transactional
    public void recordRejected(String feed, long offset, StreamMessage message, String reason, String error) {
        saveRejected(feed, offset, message, reason, error);
        cursors.saveStreamOffset(feed, offset);
    }

    private IngestResult write(StreamBatchItem.Readable item) {
        try {
            return writer.write(mapper.fromStream(item.message()));
        } catch (RejectedException e) {
            throw new BatchItemRejectedException(item.offset(), e);
        }
    }

    private void saveUnreadable(String feed, long offset, byte[] body, String reason, String error) {
        deadLetters.save(DeadLetterEntity.unreadable(feed, offset, Hashes.sha256(body), reason, error, clock.instant()));
    }

    private void saveRejected(String feed, long offset, StreamMessage message, String reason, String error) {
        deadLetters.save(DeadLetterEntity.rejected(feed, offset, message.id(), reason, error,
                objectMapper.convertValue(message, MAP), clock.instant()));
    }
}
