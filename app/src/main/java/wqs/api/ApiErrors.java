package wqs.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps request errors to RFC 9457 {@link ProblemDetail} bodies. A detail names the parameter and
 * the rule it broke and never echoes the client's value: Spring's own messages for these
 * exceptions include the rejected input, so none of them is passed through.
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missing(MissingServletRequestParameterException e) {
        return badRequest(e.getParameterName(), "Missing required parameter '" + e.getParameterName() + "'");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail unparseable(MethodArgumentTypeMismatchException e) {
        return badRequest(e.getName(), "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail invalid(InvalidParameterException e) {
        return badRequest(e.parameter(), "Parameter '" + e.parameter() + "' " + e.reason());
    }

    /** D4. */
    @ExceptionHandler(LevelNotImplementedException.class)
    ProblemDetail notImplemented(LevelNotImplementedException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED,
                "aggregationLevel " + e.level().value() + " is not implemented yet");
        p.setProperty("parameter", "aggregationLevel");
        return p;
    }

    /** Spec 9 / D2. Says nothing about why: absent, malformed and disabled look the same. */
    @ExceptionHandler(UnauthenticatedException.class)
    ProblemDetail unauthenticated(UnauthenticatedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    private static ProblemDetail badRequest(String parameter, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        p.setProperty("parameter", parameter);
        return p;
    }
}
