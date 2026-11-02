package wqs.ingest.stream;

import java.util.OptionalLong;

/** The stream, behind an interface so the consumer can be tested without a broker. */
public interface StreamSource {

    void subscribe(long fromOffset, StreamHandler handler);

    /**
     * The stream's head offset as the broker reports it, for {@code wqs.ingest.stream.offset.lag};
     * empty when unknown (no broker behind this source, or the query failed). An empty stream
     * reports -1.
     */
    default OptionalLong headOffset() {
        return OptionalLong.empty();
    }

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
