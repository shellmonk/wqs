package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import wqs.domain.CounterSentinels;

/** The filter can never carry a counter sentinel: CounterReader sums a cross product, so one would double count. */
class Level0FilterTest {

    @Test
    void blank_product_type_is_rejected_because_it_is_the_all_sentinel() {
        assertThatThrownBy(() -> new Level0Filter(1L, Set.of("keno", ""), Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Level0Filter(1L, Set.of(" "), Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_positive_type_id_is_rejected_because_zero_is_the_all_sentinel() {
        assertThatThrownBy(() -> new Level0Filter(1L, Set.of(), Set.of((short) 3, (short) 0), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Level0Filter(1L, Set.of(), Set.of((short) -1), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sentinels_are_the_documented_values() {
        assertThat(CounterSentinels.ALL_PRODUCT_TYPES).isEmpty();
        assertThat(CounterSentinels.ALL_TYPES).isZero();
    }

    @Test
    void real_values_pass_and_sets_are_defensively_copied() {
        Level0Filter f = new Level0Filter(1L, Set.of("keno"), Set.of((short) 1), true);
        assertThat(f.productTypes()).containsExactly("keno");
        assertThat(f.typeIds()).containsExactly((short) 1);
        assertThat(new Level0Filter(1L, true).productTypes()).isEmpty();
    }
}
