package wqs.api.web.archfixture;

import org.springframework.web.context.request.NativeWebRequest;

/** Fixture: reads userId through Spring's WebRequest abstraction (a subtype, NativeWebRequest). */
public class WebRequestParameterReader {
    public String leak(NativeWebRequest request) {
        return request.getParameter("userId");
    }
}
