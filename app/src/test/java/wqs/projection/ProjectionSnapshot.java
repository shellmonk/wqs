package wqs.projection;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * An order-independent, comparable view of the whole projection: items, the item locator, the
 * group locator, rollups, the page index, both counter tiers and parked cancels. Every table is
 * ordered by its full primary key, so two snapshots are equal exactly when the stored state is.
 *
 * CRITICAL: "itemId" and "ingestedAt" (item tables only) and "parkedAt" (parked cancels) are
 * excluded. itemId comes from a sequence and ingestedAt / parkedAt from now(), so they differ
 * between runs; including any would make every convergence comparison fail for reasons that have
 * nothing to do with correctness. No other projection table has a sequence- or clock-derived
 * column, so every other column is compared.
 */
public record ProjectionSnapshot(
        List<String> webItems,
        List<String> ccItems,
        List<String> locator,
        List<String> groupLocator,
        List<String> sessions,
        List<String> plays,
        List<String> webHighLevel,
        List<String> ccHighLevel,
        List<String> countDay,
        List<String> countMonth,
        List<String> pendingCancels) {

    public static ProjectionSnapshot of(DSLContext dsl) {
        return new ProjectionSnapshot(
                rows(dsl, "wqWebItem"), rows(dsl, "wqCcItem"), locatorRows(dsl),
                all(dsl, "wqGroupLocator", "\"userId\", \"batchId\", \"subBatchId\""),
                all(dsl, "wqSession", "\"userId\", \"startedAt\", \"batchId\""),
                all(dsl, "wqPlay", "\"userId\", \"startedAt\", \"batchId\", \"subBatchId\""),
                all(dsl, "wqWebHighLevel", "\"userId\", \"occurredAt\", \"kind\", \"refId\""),
                all(dsl, "wqCcHighLevel", "\"userId\", \"occurredAt\", \"kind\", \"refId\""),
                all(dsl, "wqCountDay", COUNTER_KEY),
                all(dsl, "wqCountMonth", COUNTER_KEY),
                pendingCancelRows(dsl));
    }

    private static final String COUNTER_KEY =
            "\"userId\", \"viewKind\", \"pageUnit\", \"productId\", \"period\"";

    private static List<String> rows(DSLContext dsl, String table) {
        return dsl.fetch("""
                         select "userId", "source", "sourceId", "occurredAt", "productId",
                                "batchId", "subBatchId", "type", "amount", "currency",
                                "balanceAfter", "cancelled", "cancelledBy", "cancels", "detail"
                         from "%s"
                         order by "userId", "occurredAt", "source", "sourceId"
                         """.formatted(table))
                .map(Record::toString);
    }

    private static List<String> locatorRows(DSLContext dsl) {
        return dsl.fetch("""
                         select "source", "sourceId", "userId", "occurredAt"
                         from "wqItemLocator"
                         order by "source", "sourceId"
                         """)
                .map(Record::toString);
    }

    /** Parked cancels, minus "parkedAt" (clock-derived). Converged state should hold none. */
    private static List<String> pendingCancelRows(DSLContext dsl) {
        return dsl.fetch("""
                         select "source", "cancelSourceId", "cancelsSourceId", "userId",
                                "occurredAt", "productId", "batchId", "subBatchId", "type",
                                "amount", "currency", "balanceAfter", "detail"
                         from "wqPendingCancel"
                         order by "source", "cancelSourceId"
                         """)
                .map(Record::toString);
    }

    /** Every column of a table with no volatile columns, ordered by its full primary key. */
    private static List<String> all(DSLContext dsl, String table, String primaryKey) {
        return dsl.fetch("select * from \"%s\" order by %s".formatted(table, primaryKey))
                .map(Record::toString);
    }
}
