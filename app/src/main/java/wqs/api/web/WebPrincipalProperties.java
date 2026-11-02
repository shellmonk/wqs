package wqs.api.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How /web establishes its principal (D2, O-2). {@code mode} defaults to {@link Mode#NONE}: every
 * /web request is 401 until a deployment opts in, so a stub left on cannot serve anyone's data.
 *
 * @param mode   NONE (default) or TRUSTED_HEADER
 * @param header the gateway-injected header read in TRUSTED_HEADER mode
 */
@ConfigurationProperties("wqs.web.principal")
public record WebPrincipalProperties(Mode mode, String header) {

    public static final String DEFAULT_HEADER = "X-Authenticated-User-Id";

    public enum Mode { NONE, TRUSTED_HEADER }

    public WebPrincipalProperties {
        if (mode == null) {
            mode = Mode.NONE;
        }
        if (header == null || header.isBlank()) {
            header = DEFAULT_HEADER;
        }
    }
}
