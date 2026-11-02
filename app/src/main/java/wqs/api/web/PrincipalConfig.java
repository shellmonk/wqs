package wqs.api.web;

import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the /web {@link PrincipalResolver} from {@code wqs.web.principal.mode} (D2). */
@Configuration
public class PrincipalConfig {

    private static final Logger log = LoggerFactory.getLogger(PrincipalConfig.class);

    @Bean
    public PrincipalResolver principalResolver(WebPrincipalProperties properties) {
        return switch (properties.mode()) {
            case NONE -> {
                log.info("Web principal mode is none: every /web request is answered 401 "
                        + "(set wqs.web.principal.mode to enable /web).");
                yield request -> OptionalLong.empty();
            }
            case TRUSTED_HEADER -> {
                log.warn("trusted-header principal mode: the header MUST be set only by the gateway (O-2). "
                        + "Header: {}", properties.header());
                yield new TrustedHeaderPrincipalResolver(properties.header());
            }
        };
    }
}
