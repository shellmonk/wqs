package wqs.ingest.stream;

/** The stream, behind an interface so the consumer can be tested without a broker. */
public interface StreamSource {

    void subscribe(long fromOffset, StreamHandler handler);

    void close();

    interface StreamHandler {
        void onMessage(StreamPayload payload, long offset);

        /**
         * The body at {@code offset} could not be turned into a {@link StreamPayload}. Poison:
         * the handler must record it durably (dead letter) rather than let it vanish. Abstract on
         * purpose so a handler cannot forget it.
         */
        void onUndeserializable(byte[] body, long offset, Exception error);
    }
}
