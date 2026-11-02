package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.gen.EventGenerators;

/**
 * Property P-1 (spec 8.1): applying any permutation or duplication of an event sequence
 * converges to identical projection state.
 *
 * Validates the locator idempotency gate. Phase 3 extends this to cancellations arriving on
 * either side of their originals.
 */
@JqwikSpringSupport
@SpringBootTest
class IdempotencyPropertyTest extends PostgresTestBase {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private PartitionManager partitionManager;

    @BeforeTry
    void reset() {
        partitionManager.ensurePartitionsAhead(YearMonth.from(
                EventGenerators.WINDOW_START.atZone(java.time.ZoneOffset.UTC)), 1);
        dsl().execute("delete from \"wqItemLocator\"");
        dsl().execute("delete from \"wqWebItem\"");
        dsl().execute("delete from \"wqCcItem\"");
        dsl().execute("delete from \"wqStreamOffset\"");
    }

    @Property(tries = 40)
    void a_permuted_and_duplicated_sequence_converges_to_the_same_state(
            @ForAll("sequences") List<ProjectionItem> items,
            @ForAll long seed) {

        ProjectionSnapshot inOrder = applyAndSnapshot(items);

        reset();
        ProjectionSnapshot shuffledWithDuplicates = applyAndSnapshot(perturb(items, seed));

        assertThat(shuffledWithDuplicates).isEqualTo(inOrder);
    }

    @Property(tries = 20)
    void applying_a_sequence_twice_changes_nothing(
            @ForAll("sequences") List<ProjectionItem> items) {

        ProjectionSnapshot once = applyAndSnapshot(items);
        applyBatches(items);

        assertThat(ProjectionSnapshot.of(dsl())).isEqualTo(once);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<List<ProjectionItem>> sequences() {
        return EventGenerators.itemSequences();
    }

    private ProjectionSnapshot applyAndSnapshot(List<ProjectionItem> items) {
        applyBatches(items);
        return ProjectionSnapshot.of(dsl());
    }

    /** Applies in small batches, since batch boundaries are themselves a source of reordering. */
    private void applyBatches(List<ProjectionItem> items) {
        int batchSize = 3;
        for (int from = 0; from < items.size(); from += batchSize) {
            List<ProjectionItem> slice = items.subList(from, Math.min(from + batchSize, items.size()));
            writer.apply(new ProjectionBatch("wallet-transactions", slice, (long) from));
        }
    }

    /** Shuffles and duplicates roughly a third of the events. */
    private static List<ProjectionItem> perturb(List<ProjectionItem> items, long seed) {
        Random random = new Random(seed);
        List<ProjectionItem> perturbed = new ArrayList<>(items);
        for (ProjectionItem item : items) {
            if (random.nextInt(3) == 0) {
                perturbed.add(item);
            }
        }
        Collections.shuffle(perturbed, random);
        return perturbed;
    }
}
