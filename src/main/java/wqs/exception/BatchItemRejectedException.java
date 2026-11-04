package wqs.exception;

/**
 * A message inside a batch was rejected. The batch transaction rolls back; the caller retries the
 * batch with that message recorded as rejected.
 */
public class BatchItemRejectedException extends RuntimeException {

    private final long offset;

    public BatchItemRejectedException(long offset, RejectedException cause) {
        super("stream offset " + offset + " rejected: " + cause.reason(), cause);
        this.offset = offset;
    }

    public long offset() {
        return offset;
    }

    public RejectedException rejection() {
        return (RejectedException) getCause();
    }
}
