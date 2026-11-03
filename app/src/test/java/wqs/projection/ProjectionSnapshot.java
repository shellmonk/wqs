package wqs.projection;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.db.TestSql;

/**
 * An order-independent, comparable view of the whole projection: transactions, the transaction locator, the
 * group locator, rollups, the page index, both counter tiers and parked cancels. Every table is
 * ordered by its full primary key, so two snapshots are equal exactly when the stored state is.
 *
 * CRITICAL: "transactionId" and "ingestedAt" (transaction tables only) and "parkedAt" (parked cancels) are
 * excluded. transactionId comes from a sequence and ingestedAt / parkedAt from now(), so they differ
 * between runs; including any would make every convergence comparison fail for reasons that have
 * nothing to do with correctness. No other projection table has a sequence- or clock-derived
 * column, so every other column is compared.
 */
public record ProjectionSnapshot(
        List<String> webTransactions,
        List<String> ccTransactions,
        List<String> locator,
        List<String> groupLocator,
        List<String> sessions,
        List<String> plays,
        List<String> webHighLevel,
        List<String> ccHighLevel,
        List<String> countDay,
        List<String> countMonth,
        List<String> pendingCancels) {

    public static ProjectionSnapshot of(JdbcClient jdbc) {
        TestSql sql = new TestSql(jdbc);
        return new ProjectionSnapshot(
                rows(sql, "wqPrjWebTransaction"), rows(sql, "wqPrjCcTransaction"), locatorRows(sql),
                all(sql, "wqPrjGroupLocator", "\"userId\", \"batchId\", \"subBatchId\""),
                all(sql, "wqPrjSession", "\"userId\", \"startedAt\", \"batchId\""),
                all(sql, "wqPrjPlay", "\"userId\", \"startedAt\", \"batchId\", \"subBatchId\""),
                all(sql, "wqPrjWebHighLevel", "\"userId\", \"occurredAt\", \"kind\", \"refId\""),
                all(sql, "wqPrjCcHighLevel", "\"userId\", \"occurredAt\", \"kind\", \"refId\""),
                all(sql, "wqPrjCountDay", COUNTER_KEY),
                all(sql, "wqPrjCountMonth", COUNTER_KEY),
                pendingCancelRows(sql));
    }

    private static final String COUNTER_KEY =
            "\"userId\", \"viewKind\", \"pageUnit\", \"productType\", \"type\", \"period\"";

    private static List<String> rows(TestSql sql, String table) {
        return sql.lines("""
                         select "userId", "source", "sourceId", "occurredAt", "productId",
                                "productType", "batchId", "subBatchId", "type", "amount", "currency",
                                "balanceAfter", "cancelled", "cancelledBy", "cancels", "detail"
                         from "%s"
                         order by "userId", "occurredAt", "source", "sourceId"
                         """.formatted(table));
    }

    private static List<String> locatorRows(TestSql sql) {
        return sql.lines("""
                         select "source", "sourceId", "userId", "occurredAt", "zeroAmount", "cancels"
                         from "wqPrjTransactionLocator"
                         order by "source", "sourceId"
                         """);
    }

    /** Parked cancels, minus "parkedAt" (clock-derived). Converged state should hold none. */
    private static List<String> pendingCancelRows(TestSql sql) {
        return sql.lines("""
                         select "source", "cancelSourceId", "cancelsSourceId", "userId",
                                "occurredAt", "productId", "productType", "batchId", "subBatchId", "type",
                                "amount", "currency", "balanceAfter", "detail"
                         from "wqPrjPendingCancel"
                         order by "source", "cancelSourceId"
                         """);
    }

    /** Every column of a table with no volatile columns, ordered by its full primary key. */
    private static List<String> all(TestSql sql, String table, String primaryKey) {
        return sql.lines("select * from \"%s\" order by %s".formatted(table, primaryKey));
    }
}
