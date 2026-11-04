package wqs.security;

import jakarta.servlet.http.HttpServletRequest;

/** Establishes whose data a /web request is for. /web never takes the owner id as a parameter. */
public interface OwnerIdResolver {

    /**
     * The canonical {@code "{type}-{id}"} owner id of the request's caller.
     *
     * @throws wqs.exception.UnauthenticatedException when the request carries no owner
     */
    String ownerId(HttpServletRequest request);
}
