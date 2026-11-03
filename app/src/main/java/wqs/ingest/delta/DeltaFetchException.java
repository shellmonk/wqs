package wqs.ingest.delta;

/**
 * A {@code /wallet/delta} poll that did not return a page: a connection failure or timeout, a
 * non-2xx status, or a body that is not a JSON array (plan D9). The run fails and the watermark is
 * untouched; the next fire retries. It carries no cause and never the response body (security
 * review): the HTTP status or exception class names only.
 */
public class DeltaFetchException extends RuntimeException {

    public DeltaFetchException(String message) {
        super(message);
    }
}
