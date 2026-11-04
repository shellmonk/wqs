package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wqs.domain.SourceKind;

/** Plan D1: one policy per endpoint, stating what it shows -- the one place that knows it. */
class ReadPolicyTest {

    @Test
    void web_and_cc_show_players_only_never_zero_amounts_and_their_own_sources() {
        for (ReadPolicy policy : new ReadPolicy[] {ReadPolicy.WEB, ReadPolicy.CC}) {
            assertThat(policy.ownerType()).isEqualTo((short) 1);
            assertThat(policy.includeZeroAmounts()).isFalse();
        }
        assertThat(ReadPolicy.WEB.sources())
                .containsExactlyInAnyOrder(SourceKind.TRANSACTION, SourceKind.CLAIMABLE_PRIZE);
        assertThat(ReadPolicy.CC.sources()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(SourceKind.class));
    }

    @Test
    void source_codes_are_the_sorted_bind_values() {
        assertThat(ReadPolicy.WEB.sourceCodes()).containsExactly((short) 1, (short) 3);
        assertThat(ReadPolicy.CC.sourceCodes()).containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
    }

    @Test
    void a_policy_shows_at_least_one_source() {
        assertThatThrownBy(() -> new ReadPolicy(Set.of(), (short) 1, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
