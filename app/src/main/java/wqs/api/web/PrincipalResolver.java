package wqs.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.OptionalLong;

/**
 * Establishes the /web caller's {@code userId} (spec 9). The mechanism is open question O-2; this
 * interface isolates it so it can change without touching the query pipeline. Empty means "not
 * authenticated", which /web answers with 401.
 */
public interface PrincipalResolver {

    OptionalLong resolveUserId(HttpServletRequest request);
}
