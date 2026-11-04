package wqs.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import wqs.exception.UnauthenticatedException;

/**
 * PLACEHOLDER until access-token authentication exists: reads the player id from the
 * {@code X-Player-Id} header. Anyone who can reach /web can claim any player this way, so it must
 * not be exposed beyond a trusted test setup.
 */
@Component
public class HeaderPlayerIdResolver implements PlayerIdResolver {

    public static final String HEADER = "X-Player-Id";

    @Override
    public long playerId(HttpServletRequest request) {
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException();
        }
        return PlayerIds.parse(raw.strip());
    }
}
