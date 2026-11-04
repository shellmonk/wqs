package wqs.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import wqs.exception.InvalidParameterException;
import wqs.exception.UnauthenticatedException;

/** Error responses as RFC 9457 problem details. Messages name the parameter, never echo its value. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidParameterException.class)
    public ProblemDetail invalid(InvalidParameterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("parameter", e.parameter());
        return problem;
    }

    /** A parameter that does not convert, e.g. a {@code from} that is not an ISO-8601 date-time. */
    @ExceptionHandler(BindException.class)
    public ProblemDetail unbindable(BindException e) {
        String parameter = e.getFieldErrors().isEmpty() ? "request" : e.getFieldErrors().getFirst().getField();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, parameter + " has an invalid format");
        problem.setProperty("parameter", parameter);
        return problem;
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ProblemDetail unauthenticated(UnauthenticatedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }
}
