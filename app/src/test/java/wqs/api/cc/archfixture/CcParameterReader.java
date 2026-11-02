package wqs.api.cc.archfixture;

import jakarta.servlet.http.HttpServletRequest;

/** Fixture: /cc takes userId as a parameter by design (spec 7.1), so the rule must accept this. */
public class CcParameterReader {
    public String userId(HttpServletRequest request) {
        return request.getParameter("userId");
    }
}
