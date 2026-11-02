package wqs.read;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Read-side metrics; names and tags per spec 11.2. */
@Component
public class ReadMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary residualOffset;
    private final DistributionSummary counterRows;

    public ReadMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.residualOffset = DistributionSummary.builder("wqs.read.residual.offset")
                .description("OFFSET left after the counter walk, inside the target segment")
                .register(registry);
        this.counterRows = DistributionSummary.builder("wqs.read.counter.rows")
                .description("Stored counter rows used to assemble a total")
                .register(registry);
    }

    /**
     * Times one read request. {@code endpoint} is {@code web} or {@code cc}; recorded even when
     * the request throws.
     */
    public <T> T timeRequest(String endpoint, int level, long page, Supplier<T> request) {
        return Timer.builder("wqs.read.duration")
                .tag("endpoint", endpoint)
                .tag("aggregationLevel", String.valueOf(level))
                .tag("pageDepthBucket", pageDepthBucket(page))
                .register(registry)
                .record(request);
    }

    void residualOffset(long residual) {
        residualOffset.record(residual);
    }

    void counterRows(int rows) {
        counterRows.record(rows);
    }

    void partitionsTouched(int level, int months) {
        DistributionSummary.builder("wqs.read.partitions.touched")
                .tag("aggregationLevel", String.valueOf(level))
                .register(registry)
                .record(months);
    }

    void pageSize(String endpoint, long effectivePageSize) {
        DistributionSummary.builder("wqs.read.pagesize").tag("endpoint", endpoint).register(registry)
                .record(effectivePageSize);
    }

    void totalItems(String endpoint, long total) {
        DistributionSummary.builder("wqs.read.totalitems").tag("endpoint", endpoint).register(registry)
                .record(total);
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
}
