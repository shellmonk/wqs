package wqs.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import wqs.domain.ViewKind;
import wqs.read.RecordingLevel0Query.KeysCall;

/** Test-only delegate that records every {@link #keys} call, to observe where the high-level walk lands. */
final class RecordingHighLevelQuery implements HighLevelQuery {

    private final HighLevelQuery delegate;
    final List<KeysCall> keysCalls = new ArrayList<>();
    int countCalls;

    RecordingHighLevelQuery(HighLevelQuery delegate) {
        this.delegate = delegate;
    }

    @Override
    public ViewKind view() {
        return delegate.view();
    }

    @Override
    public long count(DSLContext tx, HighLevelFilter f, Instant lo, Instant hi) {
        countCalls++;
        return delegate.count(tx, f, lo, hi);
    }

    @Override
    public List<HighLevelKey> keys(DSLContext tx, HighLevelFilter f, Instant lo, Instant hi, long offset, int limit) {
        keysCalls.add(new KeysCall(lo, hi, offset, limit));
        return delegate.keys(tx, f, lo, hi, offset, limit);
    }
}
