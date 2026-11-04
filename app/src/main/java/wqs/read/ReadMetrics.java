package wqs.read;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Read-side metrics; names and tags per spec 11.2. Every meter is registered up front, for each
 * endpoint (view), aggregation level 0-2 and page-depth bucket, so recording after a successful
 * read is a plain {@code record} call that cannot fail on registration.
 */
@Component
public class ReadMetrics {

    static final int MAX_LEVEL = 2;
    static final List<String> PAGE_DEPTH_BUCKETS = List.of("1", "2-10", "11-100", "101-1000", ">1000");

    private final DistributionSummary residualOffset;
    private final DistributionSummary counterRows;
    private final DistributionSummary[] partitionsTouched = new DistributionSummary[MAX_LEVEL + 1];
    private final Map<ViewKind, DistributionSummary> pageSize = new EnumMap<>(ViewKind.class);
    private final Map<ViewKind, DistributionSummary> totalItems = new EnumMap<>(ViewKind.class);
    /** Keyed by endpoint, level and bucket; see {@link #timerKey}. */
    private final Map<String, Timer> durations = new HashMap<>();

    public ReadMetrics(MeterRegistry registry) {
        residualOffset = DistributionSummary.builder("wqs.read.residual.offset")
                .description("OFFSET left after the counter walk, inside the target segment")
                .register(registry);
        counterRows = DistributionSummary.builder("wqs.read.counter.rows")
                .description("Stored counter rows used to assemble a total")
                .register(registry);
        for (int level = 0; level <= MAX_LEVEL; level++) {
            partitionsTouched[level] = DistributionSummary.builder("wqs.read.partitions.touched")
                    .description("Distinct UTC months among a page's rows")
                    .tag("aggregationLevel", String.valueOf(level))
                    .register(registry);
        }
        for (ViewKind view : ViewKind.values()) {
            String endpoint = endpoint(view);
            pageSize.put(view, DistributionSummary.builder("wqs.read.pagesize")
                    .description("Effective page size").tag("endpoint", endpoint).register(registry));
            totalItems.put(view, DistributionSummary.builder("wqs.read.totalitems")
                    .description("Total transactions of the requested range").tag("endpoint", endpoint).register(registry));
            for (int level = 0; level <= MAX_LEVEL; level++) {
                for (String bucket : PAGE_DEPTH_BUCKETS) {
                    durations.put(timerKey(endpoint, level, bucket), Timer.builder("wqs.read.duration")
                            .tag("endpoint", endpoint)
                            .tag("aggregationLevel", String.valueOf(level))
                            .tag("pageDepthBucket", bucket)
                            .register(registry));
                }
            }
        }
    }

    /** The {@code endpoint} tag value of a view: {@code web} or {@code cc}. */
    public static String endpoint(ViewKind view) {
        return view.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Times one read request, recorded even when it throws. {@code endpoint} is {@code web} or
     * {@code cc} and {@code level} 0-2; anything else is a programming error and fails before
     * the request runs.
     */
    public <T> T timeRequest(String endpoint, int level, long page, Supplier<T> request) {
        Timer timer = durations.get(timerKey(endpoint, level, pageDepthBucket(page)));
        if (timer == null) {
            throw new IllegalArgumentException("unknown endpoint/level: " + endpoint + "/" + level);
        }
        return timer.record(request);
    }

    void residualOffset(long residual) {
        residualOffset.record(residual);
    }

    void counterRows(int rows) {
        counterRows.record(rows);
    }

    void partitionsTouched(int level, int months) {
        partitionsTouched[level].record(months);
    }

    void pageSize(ViewKind view, long effectivePageSize) {
        pageSize.get(view).record(effectivePageSize);
    }

    void totalItems(ViewKind view, long total) {
        totalItems.get(view).record(total);
    }

    /** Low-cardinality page depth: 1 | 2-10 | 11-100 | 101-1000 | >1000. */
    static String pageDepthBucket(long page) {
        if (page <= 1) {
            return "1";
        }
        if (page <= 10) {
            return "2-10";
        }
        if (page <= 100) {
            return "11-100";
        }
        if (page <= 1000) {
            return "101-1000";
        }
        return ">1000";
    }

    private static String timerKey(String endpoint, int level, String bucket) {
        return endpoint + '|' + level + '|' + bucket;
    }
}
