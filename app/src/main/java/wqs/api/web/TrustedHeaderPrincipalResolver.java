package wqs.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.OptionalLong;

/**
 * TRUSTED_HEADER mode (D2, the gateway-injected option of O-2): the user is the value of one
 * header that the gateway sets and clients cannot. Strict by design -- anything ambiguous is "not
 * authenticated":
 *
 * <ul>
 *   <li>exactly one header value; two or more are rejected, never "take the first": a gateway
 *       that appends instead of replacing would otherwise let a client inject one;
 *   <li>after trimming HTTP whitespace (space, tab), only ASCII digits -- no sign, no {@code +},
 *       no Unicode digits, no separators;
 *   <li>a positive value that fits a long.
 * </ul>
 */
final class TrustedHeaderPrincipalResolver implements PrincipalResolver {

    private static final int MAX_LONG_DIGITS = 19;

    private final String header;

    TrustedHeaderPrincipalResolver(String header) {
        this.header = header;
    }

    @Override
    public OptionalLong resolveUserId(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(header);
        if (values == null || !values.hasMoreElements()) {
            return OptionalLong.empty();
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            return OptionalLong.empty();
        }
        return parse(value);
    }

    static OptionalLong parse(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        String v = trimHttpWhitespace(raw);
        if (v.isEmpty() || v.length() > MAX_LONG_DIGITS) {
            return OptionalLong.empty();
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalLong.empty();
            }
        }
        try {
            long id = Long.parseLong(v);
            return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
        } catch (NumberFormatException overflow) {
            return OptionalLong.empty();
        }
    }

    private static String trimHttpWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isOws(s.charAt(start))) {
            start++;
        }
        while (end > start && isOws(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }
}
