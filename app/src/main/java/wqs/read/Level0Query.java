package wqs.read;

import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import wqs.domain.ViewKind;

/** Level-0 reads of one view's item table. Stateless: every method takes the transaction's DSL. */
public interface Level0Query {

    ViewKind view();

    /** Live count of item rows in {@code [lo, hi)}. */
    long count(DSLContext tx, Level0Filter f, Instant lo, Instant hi);

    /** Keys newest-first by ("occurredAt" desc, "itemId" desc); index-only shape. */
    List<ItemKey> keys(DSLContext tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit);

    /** Payload for the keys, in the keys' order. */
    List<ItemEntry> fetch(DSLContext tx, long userId, List<ItemKey> keys);
}
