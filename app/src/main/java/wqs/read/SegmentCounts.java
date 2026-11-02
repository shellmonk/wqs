package wqs.read;

import java.util.List;

/**
 * Per-segment item counts for a decomposed range (spec 7.5). {@code counts[i]} belongs to
 * {@code segments.get(i)}; {@code counterRows} is how many stored counter rows were used.
 * Immutable: {@code counts} is copied on the way in and on the way out.
 */
public record SegmentCounts(List<Segment> segments, long[] counts, int counterRows) {

    public SegmentCounts {
        segments = List.copyOf(segments);
        counts = counts.clone();
        if (counts.length != segments.size()) {
            throw new IllegalArgumentException("one count per segment");
        }
    }

    @Override
    public long[] counts() {
        return counts.clone();
    }

    public long total() {
        long sum = 0;
        for (long c : counts) {
            sum += c;
        }
        return sum;
    }
}
