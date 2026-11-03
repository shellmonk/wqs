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
    record Stored(long userId, Instant occurredAt, long transactionId, Instant versionAt) {}

    private static final String LOCATOR = """
            select "userId", "occurredAt", "transactionId", "versionAt" from "wqPrjTransactionLocator"
            where "source" = ? and "sourceId" = ?
            """;
    private static final String CC_IMMUTABLES = """
            select "amount", "currency", "type", "productId", "productType", "batchId", "subBatchId"
            from "wqPrjCcTransaction"
            where "userId" = ? and "occurredAt" = ? and "transactionId" = ?
            """;
    private static final String UPDATE_DETAIL = """
            update "%s" set "detail" = cast(? as jsonb)
            where "userId" = ? and "occurredAt" = ? and "transactionId" = ?
            """;
    private static final String UPDATE_WEB_DETAIL = UPDATE_DETAIL.formatted("wqPrjWebTransaction");
    private static final String UPDATE_CC_DETAIL = UPDATE_DETAIL.formatted("wqPrjCcTransaction");
    private static final String UPDATE_VERSION = """
            update "wqPrjTransactionLocator" set "versionAt" = ?
            where "source" = ? and "sourceId" = ?
            """;

    /** A locator row as read; {@code versionAt} is null for a stream transaction. */
    private record Locator(long userId, OffsetDateTime occurredAt, long transactionId, OffsetDateTime versionAt) {}

    /** The immutable fields (D6) of a stored /cc transaction row. */
    private record Immutables(
            BigDecimal amount, String currency, String type, String productId, String productType,
            String batchId, String subBatchId) {}

    private final JdbcClient jdbc;

    public VersionedApplier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The row's locator, or null on first sight. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Stored stored(DeltaRow row) {
        Locator locator = jdbc.sql(LOCATOR)
                .params((short) row.source().code(), row.transaction().sourceId())
                .query((rs, n) -> new Locator(rs.getLong("userId"),
                        rs.getObject("occurredAt", OffsetDateTime.class), rs.getLong("transactionId"),
                        rs.getObject("versionAt", OffsetDateTime.class)))
                .optional()
                .orElse(null);
        if (locator == null) {
            return null;
        }
        OffsetDateTime versionAt = locator.versionAt();
        if (versionAt == null) {
            // Only stream transactions lack a version, and no polled source shares their source code.
            throw new IllegalStateException("polled row " + row.source() + "/" + row.transaction().sourceId()
                    + " has a locator without a version");
        }
        return new Stored(locator.userId(), locator.occurredAt().toInstant(),
                locator.transactionId(), versionAt.toInstant());
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
     * Compared against the /cc row: every polled source feeds /cc.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String immutableFieldChanged(DeltaRow row, Stored stored) {
        ProjectionTransaction transaction = row.transaction();
        if (transaction.userId() != stored.userId()) {
            return "userId";
        }
        if (!stored(transaction.occurredAt()).equals(stored.occurredAt())) {
            return "occurredAt";
        }
        Immutables cc = jdbc.sql(CC_IMMUTABLES)
                .params(stored.userId(), timestamptz(stored.occurredAt()), stored.transactionId())
                .query((rs, n) -> new Immutables(rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("type"), rs.getString("productId"), rs.getString("productType"),
                        rs.getString("batchId"), rs.getString("subBatchId")))
                .optional()
                .orElse(null);
        if (cc == null) {
            throw new IllegalStateException("polled row " + row.source() + "/" + transaction.sourceId()
                    + " has a locator but no /cc transaction row");
        }
        if (cc.amount().compareTo(transaction.amount()) != 0) {
            return "amount";
        }
        if (!cc.currency().stripTrailing().equals(transaction.currency())) {
            return "currency";
        }
        if (!cc.type().equals(transaction.type())) {
            return "type";
        }
        if (!Objects.equals(cc.productId(), transaction.productId())) {
            return "productId";
        }
        if (!Objects.equals(cc.productType(), transaction.productType())) {
            return "productType";
        }
        if (!Objects.equals(cc.batchId(), transaction.batchId())) {
            return "batchId";
        }
        if (!Objects.equals(cc.subBatchId(), transaction.subBatchId())) {
            return "subBatchId";
        }
        return null;
    }

    /** Replaces "detail" in every view the source feeds, then the stored version. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyNewer(DeltaRow row, Stored stored) {
        String detail = row.transaction().detailJson();
        for (ViewKind view : row.source().views()) {
            String sql = switch (view) {
                case WEB -> UPDATE_WEB_DETAIL;
                case CC -> UPDATE_CC_DETAIL;
            };
            int updated = jdbc.sql(sql)
                    .params(text(detail), stored.userId(), timestamptz(stored.occurredAt()), stored.transactionId())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("polled row " + row.source() + "/" + row.transaction().sourceId()
                        + " has no " + view + " transaction row to update");
            }
        }
        jdbc.sql(UPDATE_VERSION)
                .params(timestamptz(stored(row.version())), (short) row.source().code(),
                        row.transaction().sourceId())
                .update();
    }
}
