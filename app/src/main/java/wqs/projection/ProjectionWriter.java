package wqs.projection;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import wqs.db.PartitionProvisioner;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionTransaction;
import wqs.domain.ViewKind;
import wqs.projection.source.DeltaRow;

/**
 * The single write choke point. Nothing else in WQS holds a write connection (spec section 6).
 *
 * Implements the ordering of spec 6.2: for each transaction, the ingest rules of spec 4.1 (a
 * currency-mismatched or unsessioned eInstant transaction is dead-lettered alone), then gate and
 * insert, then (for a cancellation)
 * apply or park it, then rollups, page index and counters; after the projected transactions, drain the cancels
 * parked for projected transactions this batch inserted; then the offset last -- all in one transaction, so
 * projection state and consumer position advance or roll back together.
 *
 * <p>Everything is stored: zero-amount transactions and non-player owners take the same path as any
 * other (the owner type is part of every owner key). What an endpoint shows is decided at read
 * time by its {@code wqs.read.ReadPolicy}. A zero-amount transaction is counted apart and adds
 * nothing to a session's txCount or page index (plan D6).
 *
 * <p>{@link #applyDelta} is the polled sources' path (phase 6): versioned upserts and the
 * poller's watermark, likewise in one transaction.
 */
@Component
public class ProjectionWriter {

    private static final Logger log = LoggerFactory.getLogger(ProjectionWriter.class);

    private final TransactionInserter transactionInserter;
    private final RollupUpdater rollupUpdater;
    private final HighLevelUpdater highLevelUpdater;
    private final CounterUpdater counterUpdater;
    private final CancellationApplier cancellationApplier;
    private final PendingCancelStore pendingCancelStore;
    private final OffsetSink offsetStore;
    private final PartitionProvisioner partitionProvisioner;
    private final DrainedCancelApplier drainedCancelApplier;
    private final DrainedCancelDeadLetters drainedCancelDeadLetters;
    private final IngestRules ingestRules;
    private final RejectedTransactionDeadLetters rejectedTransactionDeadLetters;
    private final VersionedApplier versionedApplier;
    private final DeltaWatermarkStore watermarkStore;
    private final DeltaMetrics deltaMetrics;

    public ProjectionWriter(
            TransactionInserter transactionInserter,
            RollupUpdater rollupUpdater,
            HighLevelUpdater highLevelUpdater,
            CounterUpdater counterUpdater,
            CancellationApplier cancellationApplier,
            PendingCancelStore pendingCancelStore,
            OffsetSink offsetStore,
            PartitionProvisioner partitionProvisioner,
            DrainedCancelApplier drainedCancelApplier,
            DrainedCancelDeadLetters drainedCancelDeadLetters,
            IngestRules ingestRules,
            RejectedTransactionDeadLetters rejectedTransactionDeadLetters,
            VersionedApplier versionedApplier,
            DeltaWatermarkStore watermarkStore,
            DeltaMetrics deltaMetrics) {
        this.transactionInserter = transactionInserter;
        this.rollupUpdater = rollupUpdater;
        this.highLevelUpdater = highLevelUpdater;
        this.counterUpdater = counterUpdater;
        this.cancellationApplier = cancellationApplier;
        this.pendingCancelStore = pendingCancelStore;
        this.offsetStore = offsetStore;
        this.partitionProvisioner = partitionProvisioner;
        this.drainedCancelApplier = drainedCancelApplier;
        this.drainedCancelDeadLetters = drainedCancelDeadLetters;
        this.ingestRules = ingestRules;
        this.rejectedTransactionDeadLetters = rejectedTransactionDeadLetters;
        this.versionedApplier = versionedApplier;
        this.watermarkStore = watermarkStore;
        this.deltaMetrics = deltaMetrics;
    }

    /**
     * Applies the whole batch, or nothing. The offset is written in this same transaction, so a
     * failure leaves the consumer position untouched and the batch is retried from where it was.
     */
    @Transactional
    public WriteResult apply(ProjectionBatch batch) {
        // FIRST, before any projection write, and in its own transaction: no DDL ever runs inside
        // this one (spec 5.8, R100). The occurredAt months cover every partitioned row the batch
        // can write -- projected transaction rows, page-index rows (a session's re-anchored start is
        // a transaction's occurredAt) and "wqPrjCount" days. A month beyond the sanity window throws, which is poison.
        partitionProvisioner.ensureMonths(batch.transactions().stream()
                .map(transaction -> YearMonth.from(transaction.occurredAt().atZone(ZoneOffset.UTC)))
                .collect(Collectors.toSet()));

        int projected = 0;
        int duplicates = 0;
        int deadLettered = 0;
        Set<TransactionRef> inserted = new LinkedHashSet<>();

        for (int i = 0; i < batch.transactions().size(); i++) {
            ProjectionTransaction transaction = batch.transactions().get(i);

            // Rejected transactions (D6, D7) are dead-lettered alone and nothing of them is written, not
            // even the transaction row; the rest of the batch applies. Checked before the gate, so a
            // re-delivered rejected transaction is rejected again rather than counted as a duplicate.
            String rejection = ingestRules.rejection(transaction);
            if (rejection != null) {
                log.warn("Dead-lettering transaction {} (source {}): {}", transaction.sourceId(), transaction.source(), rejection);
                rejectedTransactionDeadLetters.recordRejected(batch.stream(), batch.offsetOf(i), transaction, rejection);
                deadLettered++;
                continue;
            }

            // The gate makes everything below safe to do incrementally: a replayed event never
            // gets past it, so no aggregate, counter or cancellation is ever applied twice.
            if (!transactionInserter.insertIfAbsent(transaction)) {
                duplicates++;
                continue;
            }
            projected++;
            inserted.add(TransactionRef.of(transaction));
            if (transaction.isCancellation()) {
                // Resolves against transactions committed earlier or inserted earlier in this batch;
                // otherwise parks, to be drained below or by a later batch.
                cancellationApplier.apply(transaction);
            }
            project(transaction);
        }

        // Cancels that arrived before their original: the original is now projected (by this
        // batch), so they resolve. This is what makes ingestion order-independent (spec 6.3).
        // Each is applied in its own savepoint. One that fails is rolled back alone, stays removed
        // from "wqPrjPendingCancel" and is dead-lettered ITSELF: failing the batch instead would make
        // the per-transaction fallback dead-letter the innocent original whose arrival drained it.
        for (ProjectionTransaction parked : pendingCancelStore.drainFor(inserted)) {
            try {
                drainedCancelApplier.apply(parked);
            } catch (RuntimeException e) {
                log.error("Drained cancel {} (cancels {}) failed; dead-lettering the cancel, not its original",
                        parked.sourceId(), parked.cancels(), e);
                drainedCancelDeadLetters.recordFailedDrainedCancel(batch.stream(), parked, e);
                deadLettered++;
            }
        }

        if (batch.committedOffset() != null) {
            offsetStore.write(batch.stream(), batch.committedOffset());
        }

        return new WriteResult(projected, duplicates, deadLettered);
    }

    /**
     * Applies one page of polled rows as versioned upserts (spec 4; phase 6 plan D6, D7), and the
     * poller's watermark, in one transaction: a failure leaves both untouched and the page is
     * polled again.
     *
     * <p>Per row, whatever its owner type (every owner is stored): first sight is the
     * stream's insert path -- the ingest rules, then the gate, rollups, page index and counters --
     * with the row's version recorded; zero-amount hiding does not apply (it is about transactions
     * only). A row already stored is decided by {@link VersionedApplier}: newer replaces "detail",
     * equal is a duplicate, older is stale, and a newer version changing an immutable field is
     * dead-lettered alone ({@code immutable-field-changed}) while the rest of the page applies.
     * No polled source cancels (D3), so there is nothing to park or drain.
     */
    @Transactional
    public DeltaResult applyDelta(DeltaBatch batch) {
        List<DeltaRow> rows = batch.rows();
        // As in apply(): partitions first, in their own transaction, before any projection write.
        partitionProvisioner.ensureMonths(rows.stream()
                .map(row -> YearMonth.from(row.transaction().occurredAt().atZone(ZoneOffset.UTC)))
                .collect(Collectors.toSet()));

        int inserted = 0;
        int updated = 0;
        int duplicates = 0;
        int stale = 0;
        int deadLettered = 0;
        for (DeltaRow row : rows) {
            ProjectionTransaction transaction = row.transaction();
            VersionedApplier.Stored stored = versionedApplier.stored(row);
            switch (VersionedApplier.verdict(row, stored)) {
                case ABSENT -> {
                    String rejection = ingestRules.rejection(transaction);
                    if (rejection != null) {
                        log.warn("Dead-lettering polled row {} (source {}): {}", transaction.sourceId(), transaction.source(), rejection);
                        rejectedTransactionDeadLetters.recordRejected(DeltaWatermarkStore.WALLET_DELTA, null, transaction, rejection);
                        deadLettered++;
                    } else if (transactionInserter.insertIfAbsent(transaction, VersionedApplier.stored(row.version()))) {
                        project(transaction);
                        inserted++;
                    } else {
                        duplicates++; // unreachable under the single sequential writer; never double-apply
                    }
                }
                case NEWER -> {
                    String field = versionedApplier.immutableFieldChanged(row, stored);
                    if (field != null) {
                        log.warn("Dead-lettering polled row {} (source {}): version {} changes immutable field {}",
                                transaction.sourceId(), transaction.source(), row.version(), field);
                        rejectedTransactionDeadLetters.recordRejected(DeltaWatermarkStore.WALLET_DELTA, null, transaction,
                                RejectedTransactionDeadLetters.IMMUTABLE_FIELD_CHANGED);
                        deadLettered++;
                    } else {
                        versionedApplier.applyNewer(row, stored);
                        updated++;
                    }
                }
                case DUPLICATE -> duplicates++;
                case STALE -> stale++;
            }
        }

        if (batch.newWatermark() != null) {
            watermarkStore.write(batch.newWatermark());
        }

        DeltaResult result = new DeltaResult(inserted, updated, duplicates, stale, deadLettered);
        deltaMetrics.recordOnCommit(result);
        return result;
    }

    /**
     * Rollups, page index and counters for one newly inserted transaction.
     *
     * A cancellation transaction is a transaction row like any other, so it is counted at the
     * TRANSACTION page unit (P-2: counters equal live COUNTs); the rollup and page-index updaters return
     * nothing for it, so it creates no page unit. Its effect on its original is applied by
     * {@link CancellationApplier}.
     */
    private void project(ProjectionTransaction transaction) {
        RollupResult rollup = rollupUpdater.applyTransaction(transaction);
        HighLevelResult highLevel = highLevelUpdater.applyTransaction(transaction, rollup);

        counterUpdater.countTransaction(transaction);

        // HIGH_LEVEL counters mirror the page index per view, at the row's CURRENT key: the
        // session's (start, product type) for a session row, the transaction's own for an unsessioned one.
        boolean sessioned = rollup.sessionStart() != null;
        Instant at = sessioned ? rollup.sessionStart() : transaction.occurredAt();
        String productType = sessioned ? rollup.sessionProductType() : transaction.productType();

        for (ViewKind view : highLevel.inserted()) {
            counterUpdater.countHighLevel(transaction.ownerType(), transaction.userId(), view, productType, at);
        }
        for (ViewKind view : highLevel.moved()) {
            counterUpdater.moveHighLevel(
                    transaction.ownerType(), transaction.userId(), view,
                    highLevel.movedFromProductType(), highLevel.movedFrom(),
                    productType, at,
                    highLevel.movedVisible().get(view));
        }
        // After the move: a revived row is counted in cntAll already, at the current key.
        for (ViewKind view : highLevel.revived()) {
            counterUpdater.recountExclCancelled(
                    transaction.ownerType(), transaction.userId(), view, PageUnit.HIGH_LEVEL, productType,
                    wqs.domain.CounterSentinels.ALL_TYPES, false, at);
        }
    }
}
