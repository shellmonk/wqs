package wqs.api.web.archfixture;

import jakarta.servlet.http.HttpServletRequest;

/** Fixture: a web-layer class reading userId from the request (must be rejected by the arch rule). */
public class ParameterReadingWebController {
    public String leak(HttpServletRequest request) {
        return request.getParameter("userId");
    }

    public String leakQuery(HttpServletRequest request) {
        return request.getQueryString();
    }
}
