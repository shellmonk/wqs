package wqs.security;

import wqs.exception.InvalidParameterException;

public final class PlayerIds {

    private PlayerIds() {}

    /** A player id: ASCII digits only, a long, at least 1. */
    public static long parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 19 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new InvalidParameterException("playerId", "must be a positive integer");
        }
        try {
            long id = Long.parseLong(raw);
            if (id >= 1) {
                return id;
            }
        } catch (NumberFormatException overflow) {
            // falls through
        }
        throw new InvalidParameterException("playerId", "must be a positive integer");
    }
}
