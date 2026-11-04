package wqs.ingest.stream;

/**
 * A stream message that cannot be read. The message text names the field and the rule, never the
 * value: it is stored in the dead letters, and values can be personal data.
 */
public class DecodeException extends RuntimeException {

    public DecodeException(String message) {
        super(message);
    }
}
