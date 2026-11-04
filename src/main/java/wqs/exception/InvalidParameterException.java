package wqs.exception;

/** A request parameter that fails validation; answered with 400. The message never echoes the value. */
public class InvalidParameterException extends RuntimeException {

    private final String parameter;

    public InvalidParameterException(String parameter, String rule) {
        super(parameter + " " + rule);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
