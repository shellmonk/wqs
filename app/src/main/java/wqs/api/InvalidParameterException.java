package wqs.api;

/**
 * A request parameter that parsed but is out of its domain (400). {@code reason} is fixed text
 * that names the rule, never the client's value, so the error body cannot echo user input.
 */
public class InvalidParameterException extends RuntimeException {

    private final String parameter;
    private final String reason;

    public InvalidParameterException(String parameter, String reason) {
        super(parameter + ": " + reason);
        this.parameter = parameter;
        this.reason = reason;
    }

    public String parameter() {
        return parameter;
    }

    public String reason() {
        return reason;
    }
}
