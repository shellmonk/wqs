package wqs.ingest.delta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import wqs.db.TxTypeRegistry;
import wqs.ingest.DeadLetterStore;
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
 * <p><b>Timestamp cliff.</b> A saturated page whose versions are all equal cannot be paged past by
 * a timestamp cursor (O-1): {@code wqs.delta.timestampcliff} is incremented, an ERROR logged, the
 * rows applied (idempotent) without advancing, and the run ends.
 *
 * <p><b>Poison.</b> A row that does not decode is dead-lettered alone and the rest of the page
 * applies. If the writer rejects the page, each row is retried alone and a row failing alone is
 * dead-lettered; the watermark is then written on its own. When the database itself is down the
 * dead-letter write fails too and the run fails with the watermark untouched.
 *
 * <p><b>Failure.</b> Any exception escaping a run (fetch failure, timeout, non-2xx, garbage body,
 * database) counts {@code wqs.delta.poll.failures} and fails the job; the next fire retries from
 * the stored watermark. Single-threaded: the job is {@code @DisallowConcurrentExecution} (D8).
 */
@Component
public class DeltaPoller {

    private static final Logger log = LoggerFactory.getLogger(DeltaPoller.class);
    private static final String STREAM = DeltaWatermarkStore.WALLET_DELTA;
    /** Attempts of one row alone, after its page failed, before it is dead-lettered. */
    private static final int ROW_ATTEMPTS = 2;

    private final DeltaClient client;
    private final DeltaRowDecoder decoder;
    private final TxTypeRegistry txTypes;
    private final ProjectionWriter writer;
    private final DeltaWatermarkStore watermarks;
    private final DeadLetterStore deadLetters;
    private final DeltaProperties properties;
    private final DeltaPollMetrics metrics;

    public DeltaPoller(DeltaClient client, DeltaRowDecoder decoder, TxTypeRegistry txTypes, ProjectionWriter writer,
            DeltaWatermarkStore watermarks, DeadLetterStore deadLetters, DeltaProperties properties,
            DeltaPollMetrics metrics) {
        this.client = client;
        this.decoder = decoder;
        this.txTypes = txTypes;
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

    private PollOutcome poll() {
        int pages = 0;
        int fetched = 0;
        while (true) {
            Instant stored = watermarks.read().orElse(null);
            ArrayNode page = client.fetch(since(stored));
            pages++;
            fetched += page.size();
            metrics.fetched(page.size());

            Page decoded = decode(page);
            boolean saturated = page.size() >= properties.pageLimit();
            if (saturated) {
                metrics.saturated();
            }
            boolean cliff = saturated && decoded.minVersion != null && decoded.minVersion.equals(decoded.maxVersion);
            if (cliff) {
                metrics.cliff();
                log.error("Delta timestamp cliff: a full page of {} rows all at version {}; the watermark cannot "
                        + "advance past it (O-1). Rows applied, run ended.", page.size(), decoded.maxVersion);
            }
            apply(decoded.rows, cliff ? null : decoded.maxVersion);
            metrics.watermarkMoved();

            if (cliff) {
                return new PollOutcome(pages, fetched, true);
            }
            if (!saturated || pages >= properties.maxPagesPerRun()) {
                return new PollOutcome(pages, fetched, false);
            }
            if (stored != null && (decoded.maxVersion == null || !decoded.maxVersion.isAfter(stored))) {
                // A full page entirely inside the overlap window: polling again would fetch it again.
                log.warn("Delta page of {} rows did not advance the watermark {}; more than page-limit rows "
                        + "fall within the overlap window", page.size(), stored);
                return new PollOutcome(pages, fetched, false);
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
                DeltaRow row = decoder.decode(node, txTypes);
                rows.add(row);
                version = row.version();
            } catch (IllegalArgumentException | DataIntegrityViolationException e) {
                // Poison data (as on the stream): the type registry's first-sighting insert can
                // reject the data too. Anything else is infrastructure and fails the run.
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
            if (rows.isEmpty()) {
                throw pageError;
            }
            log.warn("Delta page of {} rows failed; applying row by row", rows.size(), pageError);
        }
        for (DeltaRow row : rows) {
            applyAlone(row);
        }
        if (newWatermark != null) {
            writer.applyDelta(new DeltaBatch(List.of(), newWatermark));
        }
    }

    /** If the dead-letter write also fails (the database is down) the exception escapes. */
    private void applyAlone(DeltaRow row) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ROW_ATTEMPTS; attempt++) {
            try {
                writer.applyDelta(new DeltaBatch(List.of(row), null));
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Delta row {} (source {}) attempt {}/{} failed",
                        row.item().sourceId(), row.source(), attempt, ROW_ATTEMPTS, e);
            }
        }
        deadLetters.record(STREAM, null, row.item(), last, ROW_ATTEMPTS + 1);
        metrics.deadLettered();
        log.error("Dead-lettered delta row {} (source {})", row.item().sourceId(), row.source());
    }
}
