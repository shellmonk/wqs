package wqs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Until real authentication exists, both projection endpoints are CLOSED by default:
 * {@code /cc} answers 403 and {@code /web} answers 401. Open them only on a trusted network.
 *
 * @param ccEnabled          serve {@code /cc} (any player, every source) to any caller
 * @param webOwnerHeader    take the {@code /web} owner from the {@code X-Owner-Id} header, which any caller can set
 */
@ConfigurationProperties("wqs.api")
public record ApiAccessProperties(
        @DefaultValue("false") boolean ccEnabled,
        @DefaultValue("false") boolean webOwnerHeader) {}
