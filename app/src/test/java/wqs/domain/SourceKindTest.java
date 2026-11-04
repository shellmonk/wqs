package wqs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceKindTest {

    @Test
    void codes_are_the_stored_values() {
        assertThat(SourceKind.TRANSACTION.code()).isEqualTo(1);
        assertThat(SourceKind.MANUAL_ADJUSTMENT.code()).isEqualTo(2);
        assertThat(SourceKind.CLAIMABLE_PRIZE.code()).isEqualTo(3);
        assertThat(SourceKind.PAYMENT.code()).isEqualTo(4);
        assertThat(SourceKind.LIMIT.code()).isEqualTo(5);
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
