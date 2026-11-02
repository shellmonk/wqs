package wqs.ingest.stream;

/** The stream, behind an interface so the consumer can be tested without a broker. */
public interface StreamSource {

    void subscribe(long fromOffset, StreamHandler handler);

    void close();

    interface StreamHandler {
        void onMessage(StreamPayload payload, long offset);
    }
}
