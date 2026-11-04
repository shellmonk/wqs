package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

/** Plan D1: one policy per endpoint, stating what it shows. */
class ReadPolicyTest {

    @Test
    void web_and_cc_show_players_only_never_zero_amounts_and_their_views_sources() {
        for (ReadPolicy policy : new ReadPolicy[] {ReadPolicy.WEB, ReadPolicy.CC}) {
            assertThat(policy.ownerType()).isEqualTo((short) 1);
            assertThat(policy.includeZeroAmounts()).isFalse();
        }
        // While the views exist they must agree with the policies.
        for (ViewKind view : ViewKind.values()) {
            Set<SourceKind> fed = EnumSet.noneOf(SourceKind.class);
            for (SourceKind s : SourceKind.values()) {
                if (s.views().contains(view)) {
                    fed.add(s);
                }
            }
            assertThat((view == ViewKind.WEB ? ReadPolicy.WEB : ReadPolicy.CC).sources()).as(view.name())
                    .containsExactlyInAnyOrderElementsOf(fed);
        }
    }

    @Test
    void the_zero_amount_filter_is_a_fixed_fragment_or_nothing() {
        assertThat(ReadPolicy.WEB.zeroAmountFilter("t")).isEqualTo(" and not (t.\"zeroAmount\")");
        assertThat(new ReadPolicy(Set.of(SourceKind.TRANSACTION), (short) 1, true).zeroAmountFilter("t")).isEmpty();
        assertThatThrownBy(() -> new ReadPolicy(Set.of(), (short) 1, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
