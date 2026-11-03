package wqs.ingest.delta;

/**
 * A {@code /wallet/delta} poll that did not return a page: a connection failure or timeout, a
 * non-2xx status, or a body that is not a JSON array (plan D9). The run fails and the watermark is
 * untouched; the next fire retries.
 */
public class DeltaFetchException extends RuntimeException {

    public DeltaFetchException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeltaFetchException(String message) {
        super(message);
    }
}
