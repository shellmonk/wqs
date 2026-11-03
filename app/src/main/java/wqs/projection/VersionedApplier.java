package wqs.projection;

import static wqs.db.generated.Tables.WQ_CC_ITEM;
import static wqs.db.generated.Tables.WQ_ITEM_LOCATOR;
import static wqs.db.generated.Tables.WQ_WEB_ITEM;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;
import wqs.projection.source.DeltaRow;

/**
 * The versioned half of a polled row's upsert (spec 4; phase 6 plan D6). The locator miss -- first
 * sight -- is the writer's ordinary insert path; this decides what a row already stored means:
 * <ul>
 *   <li>strictly newer version: only "detail" (the allowlisted map, which holds the status) and
 *       "versionAt" are replaced, in every view table the source feeds -- no counter, rollup or
 *       page-index row moves, because no field they depend on may change;</li>
 *   <li>a newer version that differs in an immutable field is refused
 *       ({@code immutable-field-changed}) rather than silently retracted and re-applied (O-15);</li>
 *   <li>equal: a duplicate (the overlap window); older: stale, ignored.</li>
 * </ul>
 * Versions are compared at the column's microsecond precision, so a re-read of a stored version is
 * always equal to it.
 */
@Component
public class VersionedApplier {

    enum Verdict { ABSENT, NEWER, DUPLICATE, STALE }

    /** The stored identity of a polled row: its locator. */
    record Stored(long userId, Instant occurredAt, long itemId, Instant versionAt) {}

    private final DSLContext dsl;

    public VersionedApplier(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The row's locator, or null on first sight. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Stored stored(DeltaRow row) {
        Record locator = dsl.select(WQ_ITEM_LOCATOR.userId, WQ_ITEM_LOCATOR.occurredAt,
                        WQ_ITEM_LOCATOR.itemId, WQ_ITEM_LOCATOR.versionAt)
                .from(WQ_ITEM_LOCATOR)
                .where(WQ_ITEM_LOCATOR.source.eq((short) row.source().code()),
                       WQ_ITEM_LOCATOR.sourceId.eq(row.item().sourceId()))
                .fetchOne();
        if (locator == null) {
            return null;
        }
        OffsetDateTime versionAt = locator.get(WQ_ITEM_LOCATOR.versionAt);
        if (versionAt == null) {
            // Only stream items lack a version, and no polled source shares their source code.
            throw new IllegalStateException("polled row " + row.source() + "/" + row.item().sourceId()
                    + " has a locator without a version");
        }
        return new Stored(locator.get(WQ_ITEM_LOCATOR.userId), locator.get(WQ_ITEM_LOCATOR.occurredAt).toInstant(),
                locator.get(WQ_ITEM_LOCATOR.itemId), versionAt.toInstant());
    }

    static Verdict verdict(DeltaRow row, Stored stored) {
        if (stored == null) {
            return Verdict.ABSENT;
        }
        int order = stored(row.version()).compareTo(stored.versionAt());
        return order > 0 ? Verdict.NEWER : order == 0 ? Verdict.DUPLICATE : Verdict.STALE;
    }

    /** The version as the column stores it (timestamptz: microseconds). */
    static Instant stored(Instant version) {
        return version.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The first immutable field (D6) in which {@code row} differs from the stored item, or null.
     * Compared against the /cc row: every polled source feeds /cc.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String immutableFieldChanged(DeltaRow row, Stored stored) {
        ProjectionItem item = row.item();
        if (item.userId() != stored.userId()) {
            return "userId";
        }
        if (!stored(item.occurredAt()).equals(stored.occurredAt())) {
            return "occurredAt";
        }
        Record cc = dsl.select(WQ_CC_ITEM.amount, WQ_CC_ITEM.currency, WQ_CC_ITEM.type, WQ_CC_ITEM.productId,
                        WQ_CC_ITEM.productType, WQ_CC_ITEM.batchId, WQ_CC_ITEM.subBatchId)
                .from(WQ_CC_ITEM)
                .where(WQ_CC_ITEM.userId.eq(stored.userId()),
                       WQ_CC_ITEM.occurredAt.eq(stored.occurredAt().atOffset(ZoneOffset.UTC)),
                       WQ_CC_ITEM.itemId.eq(stored.itemId()))
                .fetchOne();
        if (cc == null) {
            throw new IllegalStateException("polled row " + row.source() + "/" + item.sourceId()
                    + " has a locator but no /cc item row");
        }
        if (cc.get(WQ_CC_ITEM.amount).compareTo(item.amount()) != 0) {
            return "amount";
        }
        if (!cc.get(WQ_CC_ITEM.currency).stripTrailing().equals(item.currency())) {
            return "currency";
        }
        if (cc.get(WQ_CC_ITEM.type) != item.type()) {
            return "type";
        }
        if (!Objects.equals(cc.get(WQ_CC_ITEM.productId), item.productId())) {
            return "productId";
        }
        if (!Objects.equals(cc.get(WQ_CC_ITEM.productType), item.productType())) {
            return "productType";
        }
        if (!Objects.equals(cc.get(WQ_CC_ITEM.batchId), item.batchId())) {
            return "batchId";
        }
        if (!Objects.equals(cc.get(WQ_CC_ITEM.subBatchId), item.subBatchId())) {
            return "subBatchId";
        }
        return null;
    }

    /** Replaces "detail" in every view the source feeds, then the stored version. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyNewer(DeltaRow row, Stored stored) {
        JSONB detail = row.item().detailJson() == null ? null : JSONB.valueOf(row.item().detailJson());
        OffsetDateTime occurredAt = stored.occurredAt().atOffset(ZoneOffset.UTC);
        for (ViewKind view : row.source().views()) {
            int updated = switch (view) {
                case WEB -> dsl.update(WQ_WEB_ITEM)
                        .set(WQ_WEB_ITEM.detail, detail)
                        .where(WQ_WEB_ITEM.userId.eq(stored.userId()),
                               WQ_WEB_ITEM.occurredAt.eq(occurredAt),
                               WQ_WEB_ITEM.itemId.eq(stored.itemId()))
                        .execute();
                case CC -> dsl.update(WQ_CC_ITEM)
                        .set(WQ_CC_ITEM.detail, detail)
                        .where(WQ_CC_ITEM.userId.eq(stored.userId()),
                               WQ_CC_ITEM.occurredAt.eq(occurredAt),
                               WQ_CC_ITEM.itemId.eq(stored.itemId()))
                        .execute();
            };
            if (updated != 1) {
                throw new IllegalStateException("polled row " + row.source() + "/" + row.item().sourceId()
                        + " has no " + view + " item row to update");
            }
        }
        dsl.update(WQ_ITEM_LOCATOR)
                .set(WQ_ITEM_LOCATOR.versionAt, stored(row.version()).atOffset(ZoneOffset.UTC))
                .where(WQ_ITEM_LOCATOR.source.eq((short) row.source().code()),
                       WQ_ITEM_LOCATOR.sourceId.eq(row.item().sourceId()))
                .execute();
    }
}
