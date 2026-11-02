package wqs.api.web;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How /web establishes its principal (D2, O-2). {@code mode} defaults to {@link Mode#NONE}: every
 * /web request is 401 until a deployment opts in, so a stub left on cannot serve anyone's data.
 *
 * @param mode   NONE (default) or TRUSTED_HEADER
 * @param header the gateway-injected header read in TRUSTED_HEADER mode. The default applies ONLY
 *               when the property is unset: a value that is present but blank, or not an HTTP
 *               field name (RFC 9110 token), fails startup. Falling back would silently switch
 *               WQS to trusting a header the gateway may not strip, letting a client pick a user.
 */
@ConfigurationProperties("wqs.web.principal")
public record WebPrincipalProperties(Mode mode, String header) {

    public static final String DEFAULT_HEADER = "X-Authenticated-User-Id";

    /** RFC 9110 5.6.2 token: 1*tchar. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    public enum Mode { NONE, TRUSTED_HEADER }

    public WebPrincipalProperties {
        if (mode == null) {
            mode = Mode.NONE;
        }
        if (header == null) {
            header = DEFAULT_HEADER;
        } else if (!TOKEN.matcher(header).matches()) {
            throw new IllegalArgumentException(
                    "wqs.web.principal.header must be an HTTP field name (RFC 9110 token); it is set but "
                            + (header.isBlank() ? "blank" : "invalid") + ". Unset it to use " + DEFAULT_HEADER);
        }
    }
}
