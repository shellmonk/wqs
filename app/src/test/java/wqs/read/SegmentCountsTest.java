package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import wqs.read.Segment.Tier;

class SegmentCountsTest {

    @Test
    void counts_are_copied_in_and_out() {
        Segment s = new Segment(Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-02-01T00:00:00Z"), Tier.MONTH);
        long[] in = {5};
        SegmentCounts sc = new SegmentCounts(List.of(s), in, 1);
        in[0] = 99;
        sc.counts()[0] = 77;
        assertThat(sc.counts()).containsExactly(5);
        assertThat(sc.total()).isEqualTo(5);
    }
}
