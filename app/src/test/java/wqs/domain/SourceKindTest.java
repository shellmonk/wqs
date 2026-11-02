package wqs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceKindTest {

    @Test
    void transactions_and_prizes_feed_both_views() {
        assertThat(SourceKind.TRANSACTION.views()).containsExactlyInAnyOrder(ViewKind.WEB, ViewKind.CC);
        assertThat(SourceKind.CLAIMABLE_PRIZE.views()).containsExactlyInAnyOrder(ViewKind.WEB, ViewKind.CC);
    }

    @Test
    void operator_only_sources_feed_cc_alone() {
        assertThat(SourceKind.PAYMENT.views()).containsExactly(ViewKind.CC);
        assertThat(SourceKind.LIMIT.views()).containsExactly(ViewKind.CC);
        assertThat(SourceKind.MANUAL_ADJUSTMENT.views()).containsExactly(ViewKind.CC);
    }

    @Test
    void codes_round_trip() {
        for (SourceKind kind : SourceKind.values()) {
            assertThat(SourceKind.ofCode(kind.code())).isEqualTo(kind);
        }
    }

    @Test
    void unknown_code_is_rejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> SourceKind.ofCode(99));
    }
}
