package wqs.ingest.delta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wqs.ingest.DeadLetterStore;
import wqs.ingest.TransientFailures;
import wqs.projection.DeltaBatch;
import wqs.projection.DeltaWatermarkStore;
import wqs.projection.ProjectionWriter;
import wqs.projection.source.DeltaRow;

/**
 * One run of the delta poll (spec 4; plan D7, D9): read the watermark, fetch the page changed since
 * it (less the overlap), decode it row by row, apply it with its new watermark in one transaction,
 * and repeat while the page came back saturated -- at most {@code max-pages-per-run} pages.
 *
 * <p><b>Watermark.</b> {@code since} = stored watermark - overlap, never below the epoch; the epoch
 * when there is none. The page's new watermark is the max version over every row whose version
 * could be read -- poison included, so one bad row cannot pin the cursor -- and the writer keeps
 * it monotonic. An empty page leaves it unchanged.
 *
 * <p><b>Timestamp cliff.</b> A saturated page that cannot move the run's cursor, with the cursor at
 * the watermark, cannot be paged past by a timestamp cursor (O-1): {@code wqs.delta.timestampcliff}
 * is incremented, an ERROR logged, the rows applied (idempotent) without advancing, and the run ends.
 *
 * <p><b>Overlap saturation.</b> A full page that does not get past the watermark lies inside the
 * overlap window ({@code wqs.delta.overlap.saturated}): the run walks the window, each next cursor
 * the previous page's max inclusive, and steps 1 µs past a version below it that fills a page
 * ({@code wqs.delta.version.overflow}; see {@link #poll()}).
 *
 * <p><b>Poison.</b> A row that does not decode is dead-lettered alone and the rest of the page
 * applies. If the writer fails the page with anything not on the transient allowlist
 * ({@link TransientFailures}), each row is applied alone, a row failing alone is dead-lettered, and
 * the watermark is then written on its own. A transient failure (pool timeout, 40P01 deadlock)
 * fails the run; nothing is dead-lettered for it.
 *
 * <p><b>Failure.</b> Any exception escaping a run (fetch failure, timeout, non-2xx, garbage body,
 * database) counts {@code wqs.delta.poll.failures} and fails the job; the next fire retries from
 * the stored watermark. Single-threaded: the job is {@code @DisallowConcurrentExecution} (D8).
 */
@Component
public class DeltaPoller {

    private static final Logger log = LoggerFactory.getLogger(DeltaPoller.class);
    private static final String STREAM = DeltaWatermarkStore.WALLET_DELTA;

    private final DeltaClient client;
    private final DeltaRowDecoder decoder;
    private final ProjectionWriter writer;
    private final DeltaWatermarkStore watermarks;
    private final DeadLetterStore deadLetters;
    private final DeltaProperties properties;
    private final DeltaPollMetrics metrics;

    public DeltaPoller(DeltaClient client, DeltaRowDecoder decoder, ProjectionWriter writer,
            DeltaWatermarkStore watermarks, DeadLetterStore deadLetters, DeltaProperties properties,
            DeltaPollMetrics metrics) {
        this.client = client;
        this.decoder = decoder;
        this.writer = writer;
        this.watermarks = watermarks;
        this.deadLetters = deadLetters;
        this.properties = properties;
        this.metrics = metrics;
    }

    public PollOutcome runOnce() {
        try {
            return poll();
        } catch (RuntimeException e) {
            metrics.failure();
            log.warn("Delta poll failed; the watermark is unchanged and the next run retries", e);
            throw e;
        }
    }

    /**
     * The run's cursor starts at W - overlap. After a full page it moves to (review I-A, I-B):
     * <ul>
     *   <li>the page's max, inclusive, while that max is not past W: the run walks the overlap
     *       window page by page, so a late commit inside it is read in this run;</li>
     *   <li>V + 1 µs, when the page could not move the cursor but the cursor is below W (more
     *       than page-limit rows share one version V below W, a bulk update): not a cliff, and late
     *       commits in (V, W) are still read; W only if the page from V + 1 µs is again at V;</li>
     *   <li>the new W - overlap once a page got past W: the normal D7 repoll.</li>
     * </ul>
     * The timestamp cliff is a full page that cannot move the cursor (min = max = cursor) with the
     * cursor at or past W (or no W): the same page would come back forever.
     */
    private PollOutcome poll() {
        int pages = 0;
        int fetched = 0;
        Instant cursor = since(watermarks.read().orElse(null));
        Instant overflowAt = null; // the version last stepped past by 1 µs
        while (true) {
            Instant stored = watermarks.read().orElse(null);
            ArrayNode page = client.fetch(cursor);
            pages++;
            fetched += page.size();
            metrics.fetched(page.size());

            Page decoded = decode(page);
            boolean saturated = page.size() >= properties.pageLimit();
            if (saturated) {
                metrics.saturated();
            }
            boolean stuck = saturated && decoded.maxVersion != null && !decoded.maxVersion.isAfter(cursor);
            boolean cliff = stuck && (stored == null || !cursor.isBefore(stored));
            if (cliff) {
                metrics.cliff();
                log.error("Delta timestamp cliff: a full page of {} rows all at version {}, the watermark; it "
                        + "cannot be paged past (O-1). Rows applied, run ended.", page.size(), decoded.maxVersion);
            }
            apply(decoded.rows, cliff ? null : decoded.maxVersion);
            metrics.watermarkMoved();

            if (cliff) {
                return new PollOutcome(pages, fetched, true);
            }
            if (!saturated || pages >= properties.maxPagesPerRun()) {
                return new PollOutcome(pages, fetched, false);
            }
            if (decoded.maxVersion == null) {
                // No readable version: polling again would fetch the same page (review M2).
                log.warn("Delta page of {} rows has no readable version; ending the run", page.size());
                return new PollOutcome(pages, fetched, false);
            }
            if (stuck) {
                if (decoded.maxVersion.equals(overflowAt)) {
                    // The step past V came back at V again (an endpoint that ignores sub-millisecond
                    // cursors): only W is left to move to.
                    log.warn("Delta page from {} returned version {} again; continuing from the watermark {}",
                            cursor, overflowAt, stored);
                    cursor = stored;
                } else {
                    // Below W (not a cliff): one version V < W fills the page. Step past V by 1 µs --
                    // versions are µs-truncated -- not to W, so late commits in (V, W) are still read
                    // (last fix J). Rows at exactly V beyond this page are unreachable without a
                    // tiebreaker (O-1).
                    metrics.versionOverflow();
                    log.warn("Delta page of {} rows all at version {} below watermark {}; rows at exactly {} "
                            + "beyond the page are not read (O-1); continuing from {} + 1 µs",
                            page.size(), decoded.maxVersion, stored, decoded.maxVersion, decoded.maxVersion);
                    overflowAt = decoded.maxVersion;
                    cursor = decoded.maxVersion.plus(1, java.time.temporal.ChronoUnit.MICROS);
                }
            } else if (stored != null && !decoded.maxVersion.isAfter(stored)) {
                // A full page inside the overlap window: walk it.
                metrics.overlapSaturated();
                cursor = decoded.maxVersion;
            } else {
                cursor = since(decoded.maxVersion);
            }
        }
    }

    private Instant since(Instant stored) {
        if (stored == null) {
            return Instant.EPOCH;
        }
        Instant since = stored.minus(properties.overlap());
        return since.isBefore(Instant.EPOCH) ? Instant.EPOCH : since;
    }

    private record Page(List<DeltaRow> rows, Instant minVersion, Instant maxVersion) {}

    private Page decode(ArrayNode page) {
        List<DeltaRow> rows = new ArrayList<>(page.size());
        Instant min = null;
        Instant max = null;
        for (JsonNode node : page) {
            Instant version;
            try {
                DeltaRow row = decoder.decode(node);
                rows.add(row);
                version = row.version();
            } catch (RuntimeException e) {
                if (TransientFailures.isTransient(e)) {
                    throw e; // infrastructure: fails the run
                }
                // Poison (as on the stream, one rule: TransientFailures): bad data or a bug.
                DeltaRowDecoder.Identity identity = decoder.identify(node);
                log.error("Cannot decode delta row {} (source {}); dead-lettering it",
                        identity.sourceId(), identity.source(), e);
                deadLetters.recordPolled(STREAM, node, DeltaRowDecoder.DEAD_LETTER_FIELDS, identity.source(), identity.sourceId(), e);
                metrics.deadLettered();
                version = identity.version();
            }
            if (version != null) {
                min = min == null || version.isBefore(min) ? version : min;
                max = max == null || version.isAfter(max) ? version : max;
            }
        }
        return new Page(rows, min, max);
    }

    private void apply(List<DeltaRow> rows, Instant newWatermark) {
        if (rows.isEmpty() && newWatermark == null) {
            return;
        }
        try {
            writer.applyDelta(new DeltaBatch(rows, newWatermark));
            return;
        } catch (RuntimeException pageError) {
            // A transient failure -- a pool timeout, a 40P01 deadlock -- fails the run. Anything
            // else is looked for row by row (one rule: TransientFailures).
            if (rows.isEmpty() || TransientFailures.isTransient(pageError)) {
                throw pageError;
            }
            log.warn("Delta page of {} rows was rejected; applying row by row", rows.size(), pageError);
        }
        for (DeltaRow row : rows) {
            applyAlone(row);
        }
        if (newWatermark != null) {
            writer.applyDelta(new DeltaBatch(List.of(), newWatermark));
        }
    }

    /**
     * A failure on the transient allowlist ({@link TransientFailures}) is rethrown, so the run fails with the watermark unchanged and the next fire retries -- rows
     * already applied alone are then duplicates. Anything else -- bad data, a bug -- is
     * dead-lettered rather than retried forever (last fix T). If the dead-letter write itself fails, that
     * escapes too.
     */
    private void applyAlone(DeltaRow row) {
        try {
            writer.applyDelta(new DeltaBatch(List.of(row), null));
        } catch (RuntimeException e) {
            if (TransientFailures.isTransient(e)) {
                throw e;
            }
            deadLetters.record(STREAM, null, row.transaction(), e, 1);
            metrics.deadLettered();
            log.error("Dead-lettered delta row {} (source {})", row.transaction().sourceId(), row.source(), e);
        }
    }
}
