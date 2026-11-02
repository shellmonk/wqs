package wqs.projection;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * An order-independent, comparable view of projection state.
 *
 * CRITICAL: "itemId" and "ingestedAt" are excluded. itemId comes from a sequence and ingestedAt
 * from now(), so both differ between runs; including either would make every convergence
 * comparison fail for reasons that have nothing to do with correctness.
 */
public record ProjectionSnapshot(List<String> webItems, List<String> ccItems, List<String> locator) {

    public static ProjectionSnapshot of(DSLContext dsl) {
        return new ProjectionSnapshot(
                rows(dsl, "wqWebItem"), rows(dsl, "wqCcItem"), locatorRows(dsl));
    }

    private static List<String> rows(DSLContext dsl, String table) {
        return dsl.fetch("""
                         select "userId", "source", "sourceId", "occurredAt", "productId",
                                "batchId", "subBatchId", "type", "amount", "currency",
                                "balanceAfter", "cancelled", "cancelledBy", "cancels", "detail"
                         from "%s"
                         order by "userId", "occurredAt", "sourceId"
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
}
