package wqs.read;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Read-side settings. {@code maxPageSize} is the optional page-size ceiling (spec 7.9, D7): null
 * (the shipped default) disables it; when set, larger requests are clamped to it.
 */
@ConfigurationProperties("wqs.read")
public record ReadProperties(Long maxPageSize) {

    public ReadProperties {
        if (maxPageSize != null && maxPageSize < 1) {
            throw new IllegalArgumentException("wqs.read.max-page-size must be >= 1 when set, was " + maxPageSize);
        }
    }
}
