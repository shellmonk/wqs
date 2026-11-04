package wqs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.IngestResult;
import wqs.dto.TotalsIncrement;
import wqs.dto.IncomingTransaction;
import wqs.exception.RejectedException;
import wqs.repository.PlayRepository;
import wqs.repository.SessionRepository;
import wqs.repository.TransactionRepository;

/**
 * Projects one row inside the caller's ingest transaction (which also stores the feed position):
 * stores it, then adds it to its session and play. A rejected row throws {@link RejectedException},
 * which rolls the whole transaction back, so a rejected row leaves no trace but its dead letter.
 *
 * <p>Order of locks: session, then play. Every writer takes them in this order.
 */
@Service
public class ProjectionWriter {

    private final PartitionService partitionService;
    private final TransactionRepository transactionRepository;
    private final SessionRepository sessionRepository;
    private final PlayRepository playRepository;
    private final ObjectMapper objectMapper;

    public ProjectionWriter(PartitionService partitionService,
                            TransactionRepository transactionRepository,
                            SessionRepository sessionRepository,
                            PlayRepository playRepository,
                            ObjectMapper objectMapper) {
        this.partitionService = partitionService;
        this.transactionRepository = transactionRepository;
        this.sessionRepository = sessionRepository;
        this.playRepository = playRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IngestResult write(IncomingTransaction t) {
        if (t.batchId() != null && !t.source().carriesBatch()) {
            throw new RejectedException("batch-on-unbatched-source", t.source() + " rows cannot carry a batch id");
        }
        if (t.batchId() == null && t.subBatchId() != null) {
            throw new RejectedException("sub-batch-without-batch", "a sub-batch id needs a batch id");
        }
        partitionService.ensure(TransactionRepository.TABLE, t.occurredAt());
        int inserted = transactionRepository.insertIfAbsent(t, json(t));
        if (inserted == 0) {
            return IngestResult.ALREADY_STORED; // already projected, totals included
        }
        if (t.batchId() != null && !t.zeroAmount()) {
            addToSessionAndPlay(TotalsIncrement.of(t));
        }
        return IngestResult.STORED;
    }

    private void addToSessionAndPlay(TotalsIncrement c) {
        long sessionId = sessionRepository.addRow(c).orElseThrow(() -> currencyMismatch("session"));
        if (c.subBatchId() == null) {
            return;
        }
        PlayRepository.Added play = playRepository.addRow(sessionId, c).orElseThrow(() -> currencyMismatch("play"));
        if (play.getCreated()) {
            sessionRepository.addPlay(sessionId);
        }
    }

    private static RejectedException currencyMismatch(String group) {
        return new RejectedException("currency-mismatch", "currency differs from the " + group + "'s currency");
    }

    private String json(IncomingTransaction t) {
        try {
            return objectMapper.writeValueAsString(t.detail());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("detail is a flat map of strings and always serialises", e);
        }
    }
}
