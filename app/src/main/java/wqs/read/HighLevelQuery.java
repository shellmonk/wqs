package wqs.read;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Level-1/2 entry reads of one endpoint (plan D4): its sessions merged with its standalone transactions. Stateless: every method takes
 * the transaction's client. One implementation per view (spec 2.2 rule 4).
 */
public interface HighLevelQuery {

    ViewKind view();

    /** What this query's endpoint shows (plan D1): every statement binds it. */
    ReadPolicy policy();

    /** Live count of entries in {@code [lo, hi)}. */
    long count(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi);

    /** Keys by ("occurredAt" desc, "kind" desc, "refId" desc): the two indexes merged. */
    List<HighLevelKey> keys(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi, long offset, int limit);
}
