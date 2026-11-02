package wqs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AggregationLevelTest {

    @Test
    void of_maps_0_to_2_and_round_trips() {
        for (int i = 0; i <= 2; i++) {
            assertThat(AggregationLevel.of(i).value()).isEqualTo(i);
        }
        assertThat(AggregationLevel.of(0)).isEqualTo(AggregationLevel.L0);
        assertThat(AggregationLevel.of(2)).isEqualTo(AggregationLevel.L2);
    }

    @Test
    void of_rejects_outside_0_to_2() {
        for (int bad : new int[] {-1, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> AggregationLevel.of(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
