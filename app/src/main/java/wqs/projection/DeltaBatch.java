package wqs.projection;

import java.time.Instant;
import java.util.List;
import wqs.projection.source.DeltaRow;

/**
 * One page of {@code /wallet/delta} rows for {@link ProjectionWriter#applyDelta}, applied in one
 * transaction together with the poller's watermark (phase 6 plan D7).
 *
 * @param newWatermark the page's max version, upserted in the same transaction as the rows; null
 *                     leaves the stored watermark unchanged (an empty page, or a timestamp cliff)
 */
public record DeltaBatch(List<DeltaRow> rows, Instant newWatermark) {

    public DeltaBatch {
        rows = List.copyOf(rows);
    }
}
