package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
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
@JqwikSpringSupport
@SpringBootTest
class IdempotencyPropertyTest extends ProjectionPropertyBase {

    @BeforeTry
    void resetBeforeTry() {
        reset();
    }

    @Property(tries = 40)
    void a_permuted_and_duplicated_sequence_converges_to_the_same_state(
            @ForAll("sequences") List<ProjectionItem> items,
            @ForAll long seed) {
        assertPermutedAndDuplicatedConverges(items, seed);
    }

    @Property(tries = 40)
    void a_permuted_and_duplicated_sequence_with_cancels_converges_to_the_same_state(
            @ForAll("sequencesWithCancels") List<ProjectionItem> items,
            @ForAll long seed) {
        assertPermutedAndDuplicatedConverges(items, seed);
    }

    @Property(tries = 20)
    void applying_a_sequence_twice_changes_nothing(
            @ForAll("sequences") List<ProjectionItem> items) {
        assertReplayChangesNothing(items);
    }

    @Property(tries = 20)
    void applying_a_sequence_with_cancels_twice_changes_nothing(
            @ForAll("sequencesWithCancels") List<ProjectionItem> items) {
        assertReplayChangesNothing(items);
    }

    private void assertPermutedAndDuplicatedConverges(List<ProjectionItem> items, long seed) {
        ProjectionSnapshot inOrder = applyAndSnapshot(items);

        reset();
        ProjectionSnapshot shuffledWithDuplicates = applyAndSnapshot(perturb(items, seed));

        assertThat(shuffledWithDuplicates).isEqualTo(inOrder);
    }

    private void assertReplayChangesNothing(List<ProjectionItem> items) {
        ProjectionSnapshot once = applyAndSnapshot(items);
        applyBatches(items);

        assertRoutedExactly(items);
        assertThat(ProjectionSnapshot.of(dsl())).isEqualTo(once);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<List<ProjectionItem>> sequences() {
        return EventGenerators.itemSequences();
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<List<ProjectionItem>> sequencesWithCancels() {
        return EventGenerators.itemSequencesWithCancels();
    }

    private ProjectionSnapshot applyAndSnapshot(List<ProjectionItem> items) {
        applyBatches(items);
        assertRoutedExactly(items);
        return ProjectionSnapshot.of(dsl());
    }

    /**
     * Convergence cannot see deterministic misrouting or deterministic dropping, so check
     * completeness directly: each distinct (source, sourceId) with a non-zero amount is in exactly
     * the view tables its source routes to (once), and nothing else is -- in particular no
     * zero-amount item; and every item, zero-amount or not, has its locator row.
     */
    private void assertRoutedExactly(List<ProjectionItem> items) {
        List<String> expectedWeb = new ArrayList<>();
        List<String> expectedCc = new ArrayList<>();
        for (ProjectionItem item : items) {
            if (item.amount().signum() == 0) continue; // locator only (spec 4.1, D8)
            String key = item.source().code() + "/" + item.sourceId();
            if (expectedWeb.contains(key) || expectedCc.contains(key)) continue; // generated keys are unique
            if (item.source().views().contains(wqs.domain.ViewKind.WEB)) expectedWeb.add(key);
            if (item.source().views().contains(wqs.domain.ViewKind.CC)) expectedCc.add(key);
        }
        assertThat(keys("wqWebItem")).as("wqWebItem").containsExactlyInAnyOrderElementsOf(expectedWeb);
        assertThat(keys("wqCcItem")).as("wqCcItem").containsExactlyInAnyOrderElementsOf(expectedCc);
        assertThat(keys("wqItemLocator")).as("wqItemLocator").containsExactlyInAnyOrderElementsOf(
                items.stream().map(i -> i.source().code() + "/" + i.sourceId()).distinct().toList());
    }

    private List<String> keys(String table) {
        return dsl().fetch("select \"source\", \"sourceId\" from \"" + table + "\"")
                .map(r -> r.get(0) + "/" + r.get(1));
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
