package wqs.api;

/** No principal could be established for a /web request (401, spec 9 / D2). */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("no authenticated principal");
    }
}
