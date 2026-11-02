package wqs.api.archfixture;

import jakarta.servlet.ServletRequest;

/** Fixture: an api class outside wqs.api.web (an advice, a filter...) reading request parameters. */
public class ParameterReadingAdvice {
    public Object leak(ServletRequest request) {
        return request.getParameterMap();
    }
}
