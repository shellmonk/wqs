package wqs.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Maps request errors to RFC 9457 {@link ProblemDetail} bodies. Nothing in a body comes from the
 * request:
 *
 * <ul>
 *   <li>a detail names the parameter and the rule it broke, never the client's value -- Spring's
 *       own messages for these exceptions include the rejected input, so none is passed through;
 *   <li>{@code instance} is set explicitly to the matched endpoint's path TEMPLATE (or
 *       {@code about:blank} when no endpoint matched). Left null, Spring fills it with the raw
 *       request URI, {@code ;matrix} content and all.
 * </ul>
 *
 * Spring picks the most specific handler, so the catch-all only sees what nothing else maps.
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    private static final URI NO_ENDPOINT = URI.create("about:blank");

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missing(MissingServletRequestParameterException e, HttpServletRequest request) {
        return badRequest(request, e.getParameterName(), "Missing required parameter '" + e.getParameterName() + "'");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail unparseable(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return badRequest(request, e.getName(), "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail invalid(InvalidParameterException e, HttpServletRequest request) {
        return badRequest(request, e.parameter(), "Parameter '" + e.parameter() + "' " + e.reason());
    }

    /** D4. */
    @ExceptionHandler(LevelNotImplementedException.class)
    ProblemDetail notImplemented(LevelNotImplementedException e, HttpServletRequest request) {
        ProblemDetail p = problem(request, HttpStatus.NOT_IMPLEMENTED,
                "aggregationLevel " + e.level().value() + " is not implemented yet");
        p.setProperty("parameter", "aggregationLevel");
        return p;
    }

    /** Spec 9 / D2. Says nothing about why: absent, malformed and disabled look the same. */
    @ExceptionHandler(UnauthenticatedException.class)
    ProblemDetail unauthenticated(UnauthenticatedException e, HttpServletRequest request) {
        return problem(request, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** O-14: /cc is deny-by-default. */
    @ExceptionHandler(CcAccessDeniedException.class)
    ProblemDetail ccDenied(CcAccessDeniedException e, HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "The /cc endpoint is disabled on this deployment");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) {
            headers.setAllow(e.getSupportedHttpMethods());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers)
                .body(problem(request, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"));
    }

    /**
     * Everything else. Spring's own client errors (404 no route, 406, ...) keep their status with
     * the status's reason phrase as fixed text; anything that is not one is a 500 whose body
     * reveals nothing. Logged with the exception, never with request values.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            log.debug("Request error {} on {}", status.value(), endpoint(request), e);
            return ResponseEntity.status(status).headers(er.getHeaders())
                    .body(problem(request, status, known == null ? "Error" : known.getReasonPhrase()));
        }
        log.error("Unhandled exception serving {}", endpoint(request), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"));
    }

    private static ProblemDetail badRequest(HttpServletRequest request, String parameter, String detail) {
        ProblemDetail p = problem(request, HttpStatus.BAD_REQUEST, detail);
        p.setProperty("parameter", parameter);
        return p;
    }

    private static ProblemDetail problem(HttpServletRequest request, HttpStatusCode status, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setInstance(endpoint(request));
        return p;
    }

    /** The matched handler's path template -- server-defined text, never the request's own URI. */
    private static URI endpoint(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return NO_ENDPOINT;
        }
        String template = pattern.toString();
        return template.startsWith("/") && template.chars().allMatch(c -> c > ' ' && c < 127 && c != '{')
                ? URI.create(template) : NO_ENDPOINT;
    }
}
