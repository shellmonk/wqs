package wqs.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import wqs.config.ApiAccessProperties;
import wqs.exception.InvalidParameterException;
import wqs.exception.UnauthenticatedException;
import wqs.util.OwnerIds;

/**
 * PLACEHOLDER until access-token authentication exists: reads the owner id ({@code "{type}-{id}"})
 * from the {@code X-Owner-Id} header. Anyone who can reach /web can claim any owner this way, so it
 * must not be exposed beyond a trusted test setup. Off unless {@code wqs.api.web-player-header=true}:
 * then every /web request is 401.
 */
@Component
public class HeaderOwnerIdResolver implements OwnerIdResolver {

    public static final String HEADER = "X-Owner-Id";

    private final ApiAccessProperties access;

    public HeaderOwnerIdResolver(ApiAccessProperties access) {
        this.access = access;
    }

    @Override
    public String ownerId(HttpServletRequest request) {
        if (!access.webPlayerHeader()) {
            throw new UnauthenticatedException();
        }
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException();
        }
        return OwnerIds.canonical(raw.strip())
                .orElseThrow(() -> new InvalidParameterException(HEADER, "must be \"{type}-{id}\""));
    }
}
