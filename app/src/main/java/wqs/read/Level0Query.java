package wqs.read;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.domain.ViewKind;

/** Level-0 reads of one view's projected transaction table. Stateless: every method takes the transaction's client. */
public interface Level0Query {

    ViewKind view();

    /** Live count of transaction rows in {@code [lo, hi)}. */
    long count(JdbcClient tx, Level0Filter f, Instant lo, Instant hi);

    /** Keys newest-first by ("occurredAt" desc, "transactionId" desc); index-only shape. */
    List<TransactionKey> keys(JdbcClient tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit);

    /** Payload for the keys, in the keys' order. */
    List<TransactionEntry> fetch(JdbcClient tx, long userId, List<TransactionKey> keys);
}
