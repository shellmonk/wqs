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
 * Property P-2 (spec 8.1): every stored counter, in both tiers, for every product key and the
 * sentinel, equals an actual COUNT over the projection -- and no other counter row exists.
 */
@JqwikSpringSupport
@SpringBootTest
class CounterConsistencyPropertyTest extends ProjectionPropertyBase {

    @Property(tries = 30)
    void counters_always_match_the_projection(
            @ForAll("sequencesWithCancels") List<ProjectionItem> items) {
        reset();
        applyBatches(items);

        assertThat(new ProjectionInvariants(dsl()).counterDrift()).isEmpty();
    }

    @Provide
    Arbitrary<List<ProjectionItem>> sequencesWithCancels() {
        return EventGenerators.itemSequencesWithCancels();
    }
}
