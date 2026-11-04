package wqs.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.Contribution;
import wqs.dto.NewTransaction;
import wqs.exception.RejectedException;
import wqs.repository.PartitionRepository;
import wqs.repository.PlayRepository;
import wqs.repository.SessionRepository;
import wqs.repository.TransactionRepository;

/**
 * Projects one row, inside the caller's ingest transaction (which also stores the feed position):
 * stores it, then adds it to its session and play. A rejected row throws {@link RejectedException}
 * and the caller's transaction rolls back, so a rejected row leaves no trace but its dead letter.
 *
 * <p>Order of locks: session, then play. Every writer takes them in this order.
 */
@Service
public class TransactionIngestService {

    public enum Outcome { INSERTED, DUPLICATE }

    private final PartitionRepository partitions;
    private final TransactionRepository transactions;
    private final SessionRepository sessions;
    private final PlayRepository plays;

    public TransactionIngestService(PartitionRepository partitions, TransactionRepository transactions,
                                    SessionRepository sessions, PlayRepository plays) {
        this.partitions = partitions;
        this.transactions = transactions;
        this.sessions = sessions;
        this.plays = plays;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome ingest(NewTransaction t) {
        if (t.batchId() != null && !t.source().carriesBatch()) {
            throw new RejectedException("batch-on-unbatched-source", t.source() + " rows cannot carry a batch id");
        }
        if (t.batchId() == null && t.subBatchId() != null) {
            throw new RejectedException("sub-batch-without-batch", "a sub-batch id needs a batch id");
        }
        partitions.ensure(TransactionRepository.TABLE, t.occurredAt());
        if (!transactions.insert(t)) {
            return Outcome.DUPLICATE; // already projected, totals included
        }
        if (t.batchId() != null && !t.zeroAmount()) {
            addToSessionAndPlay(Contribution.of(t));
        }
        return Outcome.INSERTED;
    }

    private void addToSessionAndPlay(Contribution c) {
        long sessionId = sessions.add(c).orElseThrow(() -> currencyMismatch("session"));
        if (c.subBatchId() == null) {
            return;
        }
        PlayRepository.Added play = plays.add(sessionId, c).orElseThrow(() -> currencyMismatch("play"));
        if (play.created()) {
            sessions.addPlay(sessionId);
        }
    }

    private static RejectedException currencyMismatch(String group) {
        return new RejectedException("currency-mismatch", "currency differs from the " + group + "'s currency");
    }
}
