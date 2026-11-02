package wqs.ingest.stream;

import java.util.ArrayList;
import java.util.List;

/** Drives the consumer deterministically, with no broker. */
public class FakeStreamSource implements StreamSource {

    private final List<Object[]> queued = new ArrayList<>();
    private StreamHandler handler;
    private final List<StreamHandler> handlers = new ArrayList<>();
    private boolean failOnClose;
    private final List<Object[]> history = new ArrayList<>();
    private Long subscribedFrom;
    private int closeCount;
    private int subscribeCount;

    public void queue(StreamPayload payload, long offset) {
        queued.add(new Object[] {payload, offset});
    }

    /** Delivers everything queued at or after the subscribed offset. */
    public void deliverAll() {
        history.addAll(queued);
        List<Object[]> batch = new ArrayList<>(queued);
        queued.clear();
        for (Object[] entry : batch) {
            long offset = (Long) entry[1];
            if (subscribedFrom == null || offset >= subscribedFrom) {
                handler.onMessage((StreamPayload) entry[0], offset);
            }
        }
    }

    /** Re-delivers every message ever queued whose offset is at or after the subscription point. */
    public void redeliverFromSubscription() {
        for (Object[] entry : new ArrayList<>(history)) {
            long offset = (Long) entry[1];
            if (subscribedFrom == null || offset >= subscribedFrom) {
                handler.onMessage((StreamPayload) entry[0], offset);
            }
        }
    }

    /** Delivers through the n-th handler ever subscribed (0-based), live or stale. */
    public void deliverVia(int handlerIndex, StreamPayload payload, long offset) {
        handlers.get(handlerIndex).onMessage(payload, offset);
    }

    public void failOnClose(boolean fail) {
        this.failOnClose = fail;
    }

    public int closeCount() {
        return closeCount;
    }

    public int subscribeCount() {
        return subscribeCount;
    }

    public Long subscribedFrom() {
        return subscribedFrom;
    }

    @Override
    public void subscribe(long fromOffset, StreamHandler handler) {
        this.subscribedFrom = fromOffset;
        this.subscribeCount++;
        this.handler = handler;
        handlers.add(handler);
    }

    @Override
    public void close() {
        closeCount++;
        if (failOnClose) {
            throw new IllegalStateException("close failed");
        }
    }
}
