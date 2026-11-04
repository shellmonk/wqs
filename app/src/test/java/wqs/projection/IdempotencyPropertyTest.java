package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionTransaction;
import wqs.gen.EventGenerators;

/**
 * Property P-1 (spec 8.1): applying any permutation or duplication of an event sequence
 * converges to identical projection state.
 *
 * Validates the locator idempotency gate and, through {@link ProjectionSnapshot}, everything
 * derived from it: rollups (including re-anchoring), the page index (including materialisation
 * of a session row in a view whose source arrived later) and both counter tiers (including
 * HIGH_LEVEL counter moves). Each property also runs over sequences with cancellations on either
 * side of their originals, so parking, draining across batches, hiding and revival must converge
 * too (spec 8.1 names pending-cancel parking under P-1).
 */
@Tag("property")
@JqwikSpringSupport
@SpringBootTest
class IdempotencyPropertyTest extends ProjectionPropertyBase {

    @BeforeTry
    void resetBeforeTry() {
        reset();
    }

    @Property(tries = 40)
    void a_permuted_and_duplicated_sequence_converges_to_the_same_state(
            @ForAll("sequences") List<ProjectionTransaction> transactions,
            @ForAll long seed) {
        assertPermutedAndDuplicatedConverges(transactions, seed);
    }

    @Property(tries = 40)
    void a_permuted_and_duplicated_sequence_with_cancels_converges_to_the_same_state(
            @ForAll("sequencesWithCancels") List<ProjectionTransaction> transactions,
            @ForAll long seed) {
        assertPermutedAndDuplicatedConverges(transactions, seed);
    }

    @Property(tries = 20)
    void applying_a_sequence_twice_changes_nothing(
            @ForAll("sequences") List<ProjectionTransaction> transactions) {
        assertReplayChangesNothing(transactions);
    }

    @Property(tries = 20)
    void applying_a_sequence_with_cancels_twice_changes_nothing(
            @ForAll("sequencesWithCancels") List<ProjectionTransaction> transactions) {
        assertReplayChangesNothing(transactions);
    }

    private void assertPermutedAndDuplicatedConverges(List<ProjectionTransaction> transactions, long seed) {
        ProjectionSnapshot inOrder = applyAndSnapshot(transactions);

        reset();
        ProjectionSnapshot shuffledWithDuplicates = applyAndSnapshot(perturb(transactions, seed));

        assertThat(shuffledWithDuplicates).isEqualTo(inOrder);
    }

    private void assertReplayChangesNothing(List<ProjectionTransaction> transactions) {
        ProjectionSnapshot once = applyAndSnapshot(transactions);
        applyBatches(transactions);

        assertRoutedExactly(transactions);
        assertThat(ProjectionSnapshot.of(jdbc())).isEqualTo(once);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<List<ProjectionTransaction>> sequences() {
        return EventGenerators.transactionSequences();
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<List<ProjectionTransaction>> sequencesWithCancels() {
        return EventGenerators.transactionSequencesWithCancels();
    }

    private ProjectionSnapshot applyAndSnapshot(List<ProjectionTransaction> transactions) {
        applyBatches(transactions);
        assertRoutedExactly(transactions);
        return ProjectionSnapshot.of(jdbc());
    }

    /**
     * Convergence cannot see deterministic misrouting or deterministic dropping, so check
     * completeness directly: each distinct (source, sourceId) is in exactly the view tables its
     * source routes to (once), zero-amount ones included (everything is stored; the read policy
     * hides them), and nothing else is; and every transaction is stored exactly once.
     */
    private void assertRoutedExactly(List<ProjectionTransaction> transactions) {
        List<String> expectedWeb = new ArrayList<>();
        List<String> expectedCc = new ArrayList<>();
        for (ProjectionTransaction transaction : transactions) {
            String key = transaction.source().code() + "/" + transaction.sourceId();
            if (expectedWeb.contains(key) || expectedCc.contains(key)) continue; // generated keys are unique
            if (transaction.source().views().contains(wqs.domain.ViewKind.WEB)) expectedWeb.add(key);
            if (transaction.source().views().contains(wqs.domain.ViewKind.CC)) expectedCc.add(key);
        }
        assertThat(keys("wqPrjWebTransaction")).as("wqPrjWebTransaction").containsExactlyInAnyOrderElementsOf(expectedWeb);
        assertThat(keys("wqPrjCcTransaction")).as("wqPrjCcTransaction").containsExactlyInAnyOrderElementsOf(expectedCc);
        assertThat(keys("wqPrjTransaction")).as("wqPrjTransaction").containsExactlyInAnyOrderElementsOf(
                transactions.stream().map(i -> i.source().code() + "/" + i.sourceId()).distinct().toList());
    }

    private List<String> keys(String table) {
        return db().column("select \"source\" || '/' || \"sourceId\" from \"" + table + "\"", String.class);
    }

    /** Shuffles and duplicates roughly a third of the events. */
    private static List<ProjectionTransaction> perturb(List<ProjectionTransaction> transactions, long seed) {
        Random random = new Random(seed);
        List<ProjectionTransaction> perturbed = new ArrayList<>(transactions);
        for (ProjectionTransaction transaction : transactions) {
            if (random.nextInt(3) == 0) {
                perturbed.add(transaction);
            }
        }
        Collections.shuffle(perturbed, random);
        return perturbed;
    }
}
