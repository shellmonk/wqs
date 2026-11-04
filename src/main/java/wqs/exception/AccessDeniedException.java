package wqs.exception;

/** An endpoint that is switched off; answered with 403. */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
