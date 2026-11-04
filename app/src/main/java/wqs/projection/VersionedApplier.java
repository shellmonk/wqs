package wqs.projection;

import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionTransaction;
import wqs.projection.source.DeltaRow;

/**
 * The versioned half of a polled row's upsert (spec 4; phase 6 plan D6), on "wqPrjTransaction" itself
 * (plan D2, no locator). A miss -- first sight -- is the writer's ordinary insert path; this decides what
 * a row already stored means:
 * <ul>
 *   <li>strictly newer version: only "detail" (the allowlisted map, which holds the status) and
 *       "versionAt" are replaced, on the one transaction row -- no counter, rollup or
 *       page-index row moves, because no field they depend on may change;</li>
 *   <li>a newer version that differs in an immutable field is refused
 *       ({@code immutable-field-changed}) rather than silently retracted and re-applied (O-15);</li>
 *   <li>equal: a duplicate (the overlap window); older: stale, ignored.</li>
 * </ul>
 * Versions are compared at the column's microsecond precision, so a re-read of a stored version is
 * always equal to it.
 *
 * <p><b>Lookup.</b> A polled row is found by (source, sourceId) as before the locator went: first at
 * its own "occurredAt" -- the table's unique key, one index probe in one partition, which is every
 * re-read of an unchanged row -- and only on a miss by (source, sourceId) alone, which probes the
 * unique key's index in every partition. The second probe is what keeps an "occurredAt", "userId" or
 * "ownerType" change detectable (dead-lettered, never stored as a second row) now that no global locator
 * exists; it runs once per first sight of a polled row, not per re-read.
 */
@Component
public class VersionedApplier {

    enum Verdict { ABSENT, NEWER, DUPLICATE, STALE }

    /** The stored identity of a polled row. */
    record Stored(short ownerType, long userId, Instant occurredAt, long transactionId, Instant versionAt) {}

    private static final String AT_OCCURRED_AT = """
            select "ownerType", "userId", "occurredAt", "transactionId", "versionAt" from "wqPrjTransaction"
            where "source" = ? and "sourceId" = ? and "occurredAt" = ?
            """;
    /** Earliest first, so the row found does not depend on storage (the writer never stores two). */
    private static final String ANYWHERE = """
            select "ownerType", "userId", "occurredAt", "transactionId", "versionAt" from "wqPrjTransaction"
            where "source" = ? and "sourceId" = ?
            order by "occurredAt"
            limit 1
            """;
    private static final String IMMUTABLES = """
            select "amount", "currency", "type", "productId", "productType", "batchId", "subBatchId"
            from "wqPrjTransaction"
            where "ownerType" = ? and "userId" = ? and "occurredAt" = ? and "transactionId" = ?
            """;
    private static final String UPDATE_DETAIL = """
            update "wqPrjTransaction" set "detail" = cast(? as jsonb), "versionAt" = ?
            where "ownerType" = ? and "userId" = ? and "occurredAt" = ? and "transactionId" = ?
            """;

    /** A stored row's identity as read; {@code versionAt} is null for a stream transaction. */
    private record Found(short ownerType, long userId, OffsetDateTime occurredAt, long transactionId, OffsetDateTime versionAt) {}

    /** The immutable fields (D6) of a stored transaction row. */
    private record Immutables(
            BigDecimal amount, String currency, String type, String productId, String productType,
            String batchId, String subBatchId) {}

    private static final org.springframework.jdbc.core.RowMapper<Found> FOUND_ROW = (rs, n) -> new Found(
            rs.getShort("ownerType"), rs.getLong("userId"), rs.getObject("occurredAt", OffsetDateTime.class),
            rs.getLong("transactionId"), rs.getObject("versionAt", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public VersionedApplier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The row as stored, or null on first sight. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Stored stored(DeltaRow row) {
        short source = (short) row.source().code();
        String sourceId = row.transaction().sourceId();
        Found found = jdbc.sql(AT_OCCURRED_AT)
                .params(source, sourceId, timestamptz(row.transaction().occurredAt()))
                .query(FOUND_ROW)
                .optional()
                .orElseGet(() -> jdbc.sql(ANYWHERE)
                        .params(source, sourceId)
                        .query(FOUND_ROW)
                        .optional()
                        .orElse(null));
        if (found == null) {
            return null;
        }
        OffsetDateTime versionAt = found.versionAt();
        if (versionAt == null) {
            // Only stream transactions lack a version, and no polled source shares their source code.
            throw new IllegalStateException("polled row " + row.source() + "/" + row.transaction().sourceId()
                    + " is stored without a version");
        }
        return new Stored(found.ownerType(), found.userId(), found.occurredAt().toInstant(),
                found.transactionId(), versionAt.toInstant());
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
     * The first immutable field (D6) in which {@code row} differs from the stored transaction, or null.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String immutableFieldChanged(DeltaRow row, Stored stored) {
        ProjectionTransaction transaction = row.transaction();
        if (transaction.ownerType() != stored.ownerType()) {
            return "ownerType";
        }
        if (transaction.userId() != stored.userId()) {
            return "userId";
        }
        if (!stored(transaction.occurredAt()).equals(stored.occurredAt())) {
            return "occurredAt";
        }
        Immutables current = jdbc.sql(IMMUTABLES)
                .params(stored.ownerType(), stored.userId(), timestamptz(stored.occurredAt()), stored.transactionId())
                .query((rs, n) -> new Immutables(rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("type"), rs.getString("productId"), rs.getString("productType"),
                        rs.getString("batchId"), rs.getString("subBatchId")))
                .optional()
                .orElse(null);
        if (current == null) {
            throw new IllegalStateException("polled row " + row.source() + "/" + transaction.sourceId()
                    + " vanished from wqPrjTransaction");
        }
        if (current.amount().compareTo(transaction.amount()) != 0) {
            return "amount";
        }
        if (!current.currency().stripTrailing().equals(transaction.currency())) {
            return "currency";
        }
        if (!current.type().equals(transaction.type())) {
            return "type";
        }
        if (!Objects.equals(current.productId(), transaction.productId())) {
            return "productId";
        }
        if (!Objects.equals(current.productType(), transaction.productType())) {
            return "productType";
        }
        if (!Objects.equals(current.batchId(), transaction.batchId())) {
            return "batchId";
        }
        if (!Objects.equals(current.subBatchId(), transaction.subBatchId())) {
            return "subBatchId";
        }
        return null;
    }

    /** Replaces "detail" and the stored version on the transaction row, in one statement. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyNewer(DeltaRow row, Stored stored) {
        String detail = row.transaction().detailJson();
        int updated = jdbc.sql(UPDATE_DETAIL)
                .params(text(detail), timestamptz(stored(row.version())), stored.ownerType(), stored.userId(),
                        timestamptz(stored.occurredAt()), stored.transactionId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("polled row " + row.source() + "/" + row.transaction().sourceId()
                    + " has no transaction row to update");
        }
    }
}
