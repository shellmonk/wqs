package wqs.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wallet's owner id, {@code "{type}-{id}"} (e.g. {@code 1-1717}), the service's only owner
 * identity. The type has no meaning of its own here: {@code 1-1717} and {@code 2-1717} are simply
 * different owners. {@link #canonical} gives every owner one spelling ({@code 01-1717} becomes
 * {@code 1-1717}), so the same owner can never be stored under two keys.
 */
public final class OwnerIds {

    private static final Pattern FORMAT = Pattern.compile("(\\d{1,4})-(\\d{1,18})");

    private OwnerIds() {}

    /** The canonical form of {@code raw}, or empty when it is not {@code "{type}-{id}"} with ASCII digits. */
    public static Optional<String> canonical(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(m.group(1)) + "-" + Long.parseLong(m.group(2)));
    }
}
