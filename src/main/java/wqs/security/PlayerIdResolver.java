package wqs.security;

import jakarta.servlet.http.HttpServletRequest;

/** Establishes which player a /web request is for. /web never takes the player id as a parameter. */
public interface PlayerIdResolver {

    /** @throws wqs.exception.UnauthenticatedException when the request carries no player */
    long playerId(HttpServletRequest request);
}
