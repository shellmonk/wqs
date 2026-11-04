package wqs.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import wqs.config.ApiAccessProperties;
import wqs.exception.UnauthenticatedException;

/**
 * PLACEHOLDER until access-token authentication exists: reads the player id from the
 * {@code X-Player-Id} header. Anyone who can reach /web can claim any player this way, so it must
 * not be exposed beyond a trusted test setup. Off unless {@code wqs.api.web-player-header=true}:
 * then every /web request is 401.
 */
@Component
public class HeaderPlayerIdResolver implements PlayerIdResolver {

    public static final String HEADER = "X-Player-Id";

    private final ApiAccessProperties access;

    public HeaderPlayerIdResolver(ApiAccessProperties access) {
        this.access = access;
    }

    @Override
    public long playerId(HttpServletRequest request) {
        if (!access.webPlayerHeader()) {
            throw new UnauthenticatedException();
        }
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException();
        }
        return PlayerIds.parse(raw.strip());
    }
}
