package wqs.exception;

/**
 * A message inside a batch was refused. The batch transaction rolls back; the caller retries the
 * batch with that message recorded as refused.
 */
public class BatchItemRejectedException extends RuntimeException {

    private final long offset;

    public BatchItemRejectedException(long offset, RejectedException cause) {
        super("stream offset " + offset + " refused: " + cause.reason(), cause);
        this.offset = offset;
    }

    public long offset() {
        return offset;
    }

    public RejectedException rejection() {
        return (RejectedException) getCause();
    }
}
