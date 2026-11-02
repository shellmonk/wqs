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

    /** Item 3 of the review: recording after a successful read must never hit a registration. */
    @Test
    void every_meter_is_registered_up_front() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ReadMetrics(registry);

        assertThat(registry.find("wqs.read.residual.offset").summaries()).hasSize(1);
        assertThat(registry.find("wqs.read.counter.rows").summaries()).hasSize(1);
        for (int level = 0; level <= 2; level++) {
            assertThat(registry.find("wqs.read.partitions.touched").tag("aggregationLevel", String.valueOf(level))
                    .summary()).isNotNull();
        }
        for (String endpoint : new String[] {"web", "cc"}) {
            assertThat(registry.find("wqs.read.pagesize").tag("endpoint", endpoint).summary()).isNotNull();
            assertThat(registry.find("wqs.read.totalitems").tag("endpoint", endpoint).summary()).isNotNull();
            assertThat(registry.find("wqs.read.duration").tag("endpoint", endpoint).timers()).hasSize(3 * 5);
        }
        assertThat(registry.find("wqs.read.duration").timers()).hasSize(2 * 3 * 5);
    }

    @Test
    void time_request_rejects_an_unknown_endpoint_before_running_the_request() {
        ReadMetrics metrics = new ReadMetrics(new SimpleMeterRegistry());
        boolean[] ran = {false};
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> metrics.timeRequest("nope", 0, 1, () -> ran[0] = true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ran[0]).isFalse();
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
        assertThat(registry.find("wqs.read.duration").tags("endpoint", "cc", "aggregationLevel", "0", "pageDepthBucket", "1").timer().count())
                .isEqualTo(1);
    }
}
