package wqs.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import wqs.domain.ViewKind;

/** Test-only delegate that records every {@link #keys} call, to observe where the offset walk lands. */
final class RecordingLevel0Query implements Level0Query {

    record KeysCall(Instant lo, Instant hi, long offset, int limit) {}

    private final Level0Query delegate;
    final List<KeysCall> keysCalls = new ArrayList<>();
    int countCalls;
    int fetchCalls;

    RecordingLevel0Query(Level0Query delegate) {
        this.delegate = delegate;
    }

    @Override
    public ViewKind view() {
        return delegate.view();
    }

    @Override
    public long count(DSLContext tx, Level0Filter f, Instant lo, Instant hi) {
        countCalls++;
        return delegate.count(tx, f, lo, hi);
    }

    @Override
    public List<ItemKey> keys(DSLContext tx, Level0Filter f, Instant lo, Instant hi, long offset, int limit) {
        keysCalls.add(new KeysCall(lo, hi, offset, limit));
        return delegate.keys(tx, f, lo, hi, offset, limit);
    }

    @Override
    public List<ItemEntry> fetch(DSLContext tx, long userId, List<ItemKey> keys) {
        fetchCalls++;
        return delegate.fetch(tx, userId, keys);
    }
}
