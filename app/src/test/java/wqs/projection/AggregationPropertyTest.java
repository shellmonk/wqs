package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
import wqs.gen.EventGenerators;

/**
 * Property P-4 (spec 8.1): every session and play row equals a fold over its items (counts,
 * money and their ExclCancelled forms, bounds, product, currency; net = wins - plays), and the
 * group locator points at each row.
 */
@JqwikSpringSupport
@SpringBootTest
class AggregationPropertyTest extends ProjectionPropertyBase {

    @Property(tries = 30)
    void rollups_always_equal_a_fold_over_their_items(
            @ForAll("sequencesWithCancels") List<ProjectionItem> items) {
        reset();
        applyBatches(items);

        assertThat(new ProjectionInvariants(dsl()).aggregationDrift()).isEmpty();
    }

    @Provide
    Arbitrary<List<ProjectionItem>> sequencesWithCancels() {
        return EventGenerators.itemSequencesWithCancels();
    }
}
