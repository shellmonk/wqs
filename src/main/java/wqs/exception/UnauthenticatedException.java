package wqs.exception;

/** A /web request without a player; answered with 401. */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("no authenticated player");
    }
}
