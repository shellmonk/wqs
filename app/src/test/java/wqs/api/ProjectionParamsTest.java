package wqs.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Range normalisation in {@link ProjectionParams#validate}: microsecond truncation and the data-domain clamp. */
class ProjectionParamsTest {

    private static final Instant DOMAIN_START = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant DOMAIN_END = Instant.parse("2200-01-01T00:00:00Z");

    private static ProjectionParams params(String from, String to) {
        return ProjectionParams.validate(Instant.parse(from), Instant.parse(to), 0, null, false, 1, 10);
    }

    @Test
    void the_domain_constants_are_the_ruled_bounds() {
        assertThat(wqs.domain.DataDomain.START).isEqualTo(DOMAIN_START);
        assertThat(wqs.domain.DataDomain.END).isEqualTo(DOMAIN_END);
    }

    @Test
    void bounds_inside_the_domain_are_unchanged() {
        ProjectionParams p = params("2034-02-01T00:00:00Z", "2034-03-01T00:00:00Z");
        assertThat(p.from()).isEqualTo(Instant.parse("2034-02-01T00:00:00Z"));
        assertThat(p.to()).isEqualTo(Instant.parse("2034-03-01T00:00:00Z"));
    }

    @Test
    void bounds_outside_the_domain_are_clamped() {
        ProjectionParams p = params("-0001-01-01T00:00:00Z", "+1000000-01-01T00:00:00Z");
        assertThat(p.from()).isEqualTo(DOMAIN_START);
        assertThat(p.to()).isEqualTo(DOMAIN_END);
        assertThat(params("1969-12-31T23:59:59Z", "2200-01-01T00:00:01Z").from()).isEqualTo(DOMAIN_START);
        assertThat(params("1969-12-31T23:59:59Z", "2200-01-01T00:00:01Z").to()).isEqualTo(DOMAIN_END);
        assertThat(params("1970-01-01T00:00:00Z", "2200-01-01T00:00:00Z").from()).isEqualTo(DOMAIN_START);
    }

    @Test
    void both_bounds_beyond_the_same_edge_collapse_to_an_empty_range() {
        ProjectionParams future = params("+300000-01-01T00:00:00Z", "+1000000-01-01T00:00:00Z");
        assertThat(future.from()).isEqualTo(DOMAIN_END).isEqualTo(future.to());
        ProjectionParams past = params("-5000-01-01T00:00:00Z", "1900-01-01T00:00:00Z");
        assertThat(past.from()).isEqualTo(DOMAIN_START).isEqualTo(past.to());
    }

    /** The from > to check sees the ORIGINAL values: an inverted request stays a 400 even when both clamp equal. */
    @Test
    void inverted_bounds_beyond_the_domain_are_still_rejected() {
        assertThatThrownBy(() -> params("+1000000-01-01T00:00:00Z", "+300000-01-01T00:00:00Z"))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("from");
    }

    /** M3: Postgres stores microseconds, so the reader must never see a sub-microsecond bound. */
    @Test
    void bounds_are_truncated_to_microseconds() {
        ProjectionParams p = params("2034-02-01T00:00:00.000001999Z", "2034-02-12T10:00:00.000000600Z");
        assertThat(p.from()).isEqualTo(Instant.parse("2034-02-01T00:00:00.000001Z"));
        assertThat(p.to()).isEqualTo(Instant.parse("2034-02-12T10:00:00Z"));
    }
}
