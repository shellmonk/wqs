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
 * Property P-8 (spec 8.1): each view's page index holds exactly one row per session with an item
 * in that view and one per unsessioned item, at the right key, with visibility agreeing with the
 * underlying rows.
 *
 * This property exists because the index is a MATERIALISED merge. A read-time union could never
 * disagree with its sources; a materialised one can.
 */
@JqwikSpringSupport
@SpringBootTest
class HighLevelIndexPropertyTest extends ProjectionPropertyBase {

    @Property(tries = 30)
    void the_page_index_always_reconciles_with_its_sources(
            @ForAll("sequencesWithCancels") List<ProjectionItem> items) {
        reset();
        applyBatches(items);

        assertThat(new ProjectionInvariants(dsl()).highLevelDrift()).isEmpty();
        // The cancellations that drive visibility are themselves exactly right (spec 6.3).
        assertThat(new ProjectionInvariants(dsl()).cancellationDrift()).isEmpty();
    }

    @Provide
    Arbitrary<List<ProjectionItem>> sequencesWithCancels() {
        return EventGenerators.itemSequencesWithCancels();
    }
}
