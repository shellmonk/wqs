package wqs.read;

import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import wqs.domain.ViewKind;

/**
 * Level-1/2 reads of one view's high-level page index (spec 5.6). Stateless: every method takes
 * the transaction's DSL. One implementation per view (spec 2.2 rule 4).
 */
public interface HighLevelQuery {

    ViewKind view();

    /** Live count of page-index rows in {@code [lo, hi)}. */
    long count(DSLContext tx, HighLevelFilter f, Instant lo, Instant hi);

    /** Keys by ("occurredAt" desc, "kind" desc, "refId" desc); a covering index scan. */
    List<HighLevelKey> keys(DSLContext tx, HighLevelFilter f, Instant lo, Instant hi, long offset, int limit);
}
