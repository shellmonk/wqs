package wqs.projection;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.gen.EventGenerators;

/** Shared setup for the projection properties (spec 8.1). */
public abstract class ProjectionPropertyBase extends PostgresTestBase {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // PostgresTestBase has already migrated the shared container.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired protected ProjectionWriter writer;
    @Autowired protected PartitionManager partitionManager;

    /** Empties the projection; the generator's window needs its month and the next. */
    protected void reset() {
        partitionManager.ensurePartitionsAhead(
                YearMonth.from(EventGenerators.WINDOW_START.atZone(ZoneOffset.UTC)), 1);
        clearProjection();
    }

    /** Small batches, since batch boundaries are themselves a source of reordering. */
    protected void applyBatches(List<ProjectionTransaction> transactions) {
        int batchSize = 3;
        for (int from = 0; from < transactions.size(); from += batchSize) {
            List<ProjectionTransaction> slice =
                    transactions.subList(from, Math.min(from + batchSize, transactions.size()));
            writer.apply(new ProjectionBatch("wallet-transactions", slice, (long) from));
        }
    }
}
