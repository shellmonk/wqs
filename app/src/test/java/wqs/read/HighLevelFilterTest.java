package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wqs.domain.AggregationLevel;

/** The level-1/2 filter can never carry the ALL sentinel; a level-1/2 request is never level 0. */
class HighLevelFilterTest {

    @Test
    void blank_product_type_is_rejected_because_it_is_the_all_sentinel() {
        assertThatThrownBy(() -> new HighLevelFilter(1L, Set.of("keno", ""), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HighLevelFilter(1L, Set.of(" "), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void real_values_pass_and_the_set_is_defensively_copied() {
        java.util.HashSet<String> types = new java.util.HashSet<>(Set.of("keno"));
        HighLevelFilter f = new HighLevelFilter(1L, types, true);
        types.add("lottery");
        assertThat(f.productTypes()).containsExactly("keno");
        assertThat(new HighLevelFilter(1L, false).productTypes()).isEmpty();
    }

    @Test
    void a_high_level_request_is_level_1_or_2() {
        HighLevelFilter f = new HighLevelFilter(1L, false);
        assertThatThrownBy(() -> new HighLevelRequest(
                        ViewKind.WEB, AggregationLevel.L0, f, Instant.EPOCH, Instant.EPOCH, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HighLevelRequest(ViewKind.WEB, null, f, Instant.EPOCH, Instant.EPOCH, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new HighLevelRequest(ViewKind.CC, AggregationLevel.L2, f, Instant.EPOCH, Instant.EPOCH, 1, 1).level())
                .isEqualTo(AggregationLevel.L2);
    }
}
