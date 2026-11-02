package wqs.ingest.stream;

import java.util.ArrayList;
import java.util.List;

/** Drives the consumer deterministically, with no broker. */
public class FakeStreamSource implements StreamSource {

    private final List<Object[]> queued = new ArrayList<>();
    private StreamHandler handler;
    private Long subscribedFrom;

    public void queue(StreamPayload payload, long offset) {
        queued.add(new Object[] {payload, offset});
    }

    /** Delivers everything queued at or after the subscribed offset. */
    public void deliverAll() {
        for (Object[] entry : queued) {
            long offset = (Long) entry[1];
            if (subscribedFrom == null || offset >= subscribedFrom) {
                handler.onMessage((StreamPayload) entry[0], offset);
            }
        }
        queued.clear();
    }

    public Long subscribedFrom() {
        return subscribedFrom;
    }

    @Override
    public void subscribe(long fromOffset, StreamHandler handler) {
        this.subscribedFrom = fromOffset;
        this.handler = handler;
    }

    @Override
    public void close() {}
}
