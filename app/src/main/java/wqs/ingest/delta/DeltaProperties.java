package wqs.ingest.delta;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import wqs.domain.SourceKind;

/**
 * The {@code /wallet/delta} wire details, which are configuration rather than code (phase 6
 * plan D1, D2, D7): a real sample later changes only these values, the decoder and its fixtures.
 *
 * @param baseUrl        the wallet service; the request is {@code GET {baseUrl}/wallet/delta?{cursorParam}=...}
 * @param cursorParam    the query parameter carrying the ISO-8601 UTC cursor (default {@code from})
 * @param pageLimit      the response's row limit (default 1000); a full page is "saturated"
 * @param overlap        how far before the stored watermark the next poll starts (default 120 s)
 * @param maxPagesPerRun saturated pages polled back to back within one job run (default 20)
 * @param sources        the row's {@code source} value -> its kind, matched case-insensitively;
 *                       when set it replaces the defaults
 * @param connectTimeout the HTTP connect timeout (default 2 s)
 * @param readTimeout    the HTTP read timeout (default 10 s)
 */
@ConfigurationProperties("wqs.delta")
public record DeltaProperties(
        String baseUrl,
        String cursorParam,
        int pageLimit,
        Duration overlap,
        int maxPagesPerRun,
        Map<String, SourceKind> sources,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Map<String, SourceKind> DEFAULT_SOURCES = Map.of(
            "walPayments", SourceKind.PAYMENT,
            "walClaimablePrizes", SourceKind.CLAIMABLE_PRIZE,
            "walManualAdjustmentRequests", SourceKind.MANUAL_ADJUSTMENT,
            "walLimits", SourceKind.LIMIT);

    public DeltaProperties {
        if (cursorParam == null) {
            cursorParam = "from";
        } else if (cursorParam.isBlank()) {
            throw new IllegalArgumentException("wqs.delta.cursor-param must not be blank");
        }
        if (pageLimit < 0) {
            throw new IllegalArgumentException("wqs.delta.page-limit must be positive");
        }
        if (pageLimit == 0) {
            pageLimit = 1000;
        }
        if (overlap == null) {
            overlap = Duration.ofSeconds(120);
        } else if (overlap.isNegative()) {
            throw new IllegalArgumentException("wqs.delta.overlap must not be negative");
        }
        if (maxPagesPerRun < 0) {
            throw new IllegalArgumentException("wqs.delta.max-pages-per-run must be positive");
        }
        if (maxPagesPerRun == 0) {
            maxPagesPerRun = 20;
        }
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(2), "connect-timeout");
        readTimeout = positive(readTimeout, Duration.ofSeconds(10), "read-timeout");
        Map<String, SourceKind> given = sources == null || sources.isEmpty() ? DEFAULT_SOURCES : sources;
        Map<String, SourceKind> normalised = new LinkedHashMap<>();
        given.forEach((value, kind) -> {
            if (value == null || value.isBlank() || kind == null) {
                throw new IllegalArgumentException("wqs.delta.sources entries must name a value and a kind");
            }
            if (kind == SourceKind.TRANSACTION) {
                throw new IllegalArgumentException(
                        "wqs.delta.sources." + value + ": transactions come from the stream, never the delta poll");
            }
            normalised.put(value.toLowerCase(Locale.ROOT), kind);
        });
        sources = Map.copyOf(normalised);
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("wqs.delta." + name + " must be positive");
        }
        return value;
    }

    /** The kind a row's {@code source} value names, case-insensitively; empty if unmapped (poison, D2). */
    public Optional<SourceKind> sourceOf(String value) {
        return value == null ? Optional.empty() : Optional.ofNullable(sources.get(value.toLowerCase(Locale.ROOT)));
    }
}
