package wqs.exception;

/**
 * A readable row the projection refuses. It rolls the ingest transaction back; the caller then
 * dead-letters the row under {@link #reason()} in a fresh transaction. The message names the rule,
 * never a value: it is stored, and values can be personal data.
 */
public class RejectedException extends RuntimeException {

    private final String reason;

    public RejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** A stable, kebab-case code stored as the dead letter's reason, e.g. {@code currency-mismatch}. */
    public String reason() {
        return reason;
    }
}
