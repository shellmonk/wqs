package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ReadMetricsTest {

    @Test
    void page_depth_buckets_split_on_their_boundaries() {
        assertThat(ReadMetrics.pageDepthBucket(1)).isEqualTo("1");
        assertThat(ReadMetrics.pageDepthBucket(2)).isEqualTo("2-10");
        assertThat(ReadMetrics.pageDepthBucket(10)).isEqualTo("2-10");
        assertThat(ReadMetrics.pageDepthBucket(11)).isEqualTo("11-100");
        assertThat(ReadMetrics.pageDepthBucket(100)).isEqualTo("11-100");
        assertThat(ReadMetrics.pageDepthBucket(101)).isEqualTo("101-1000");
        assertThat(ReadMetrics.pageDepthBucket(1000)).isEqualTo("101-1000");
        assertThat(ReadMetrics.pageDepthBucket(1001)).isEqualTo(">1000");
        assertThat(ReadMetrics.pageDepthBucket(Long.MAX_VALUE)).isEqualTo(">1000");
    }

    @Test
    void time_request_records_duration_with_tags_and_returns_the_result() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReadMetrics metrics = new ReadMetrics(registry);

        String out = metrics.timeRequest("web", 0, 42, () -> "body");

        assertThat(out).isEqualTo("body");
        Timer t = registry.find("wqs.read.duration")
                .tags("endpoint", "web", "aggregationLevel", "0", "pageDepthBucket", "11-100").timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(1);
    }

    @Test
    void time_request_records_even_when_the_request_throws() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReadMetrics metrics = new ReadMetrics(registry);
        try {
            metrics.timeRequest("cc", 0, 1, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // timed regardless
        }
        assertThat(registry.find("wqs.read.duration").tags("endpoint", "cc", "pageDepthBucket", "1").timer().count())
                .isEqualTo(1);
    }
}
