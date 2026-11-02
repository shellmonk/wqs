package wqs.api.cc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import wqs.api.CcAccessDeniedException;

/**
 * Deny-by-default gate for /cc (O-14). In DENY mode an interceptor rejects every request routed to
 * a {@code wqs.api.cc} handler with 403. It runs in {@code preHandle}, i.e. after routing but
 * BEFORE argument binding, validation or any read. It matches on the resolved handler rather than
 * a path pattern, so path variants (matrix parameters, encodings) cannot route around it.
 */
@Configuration
public class CcAccessConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CcAccessConfig.class);

    private final CcAccessProperties properties;

    public CcAccessConfig(CcAccessProperties properties) {
        this.properties = properties;
        if (properties.access() == CcAccessProperties.Access.OPEN) {
            log.warn("/cc access is OPEN (wqs.cc.access=open): /cc has no authentication yet (O-14) and "
                    + "serves any userId to any caller. Open it only on a trusted network.");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (properties.access() == CcAccessProperties.Access.DENY) {
            registry.addInterceptor(new DenyCc());
        }
    }

    private static final class DenyCc implements HandlerInterceptor {

        private static final String CC_PACKAGE = CcAccessConfig.class.getPackageName();

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (handler instanceof HandlerMethod hm && hm.getBeanType().getPackageName().startsWith(CC_PACKAGE)) {
                throw new CcAccessDeniedException();
            }
            return true;
        }
    }
}
