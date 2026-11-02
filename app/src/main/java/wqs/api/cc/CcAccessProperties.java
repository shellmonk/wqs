package wqs.api.cc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether /cc serves requests (O-14: /cc has no authentication yet). DENY, the default, answers
 * every /cc request 403; OPEN serves it and must be used only on a trusted network. Bound as an
 * enum, so an unknown value fails startup rather than defaulting.
 */
@ConfigurationProperties("wqs.cc")
public record CcAccessProperties(Access access) {

    public enum Access { DENY, OPEN }

    public CcAccessProperties {
        if (access == null) {
            access = Access.DENY;
        }
    }
}
