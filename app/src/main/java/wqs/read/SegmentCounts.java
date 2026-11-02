package wqs.read;

import java.util.List;

/**
 * Per-segment item counts for a decomposed range (spec 7.5). {@code counts[i]} belongs to
 * {@code segments.get(i)}; {@code counterRows} is how many stored counter rows were used.
 */
public record SegmentCounts(List<Segment> segments, long[] counts, int counterRows) {

    public SegmentCounts {
        segments = List.copyOf(segments);
        if (counts.length != segments.size()) {
            throw new IllegalArgumentException("one count per segment");
        }
    }

    public long total() {
        long sum = 0;
        for (long c : counts) {
            sum += c;
        }
        return sum;
    }
}
