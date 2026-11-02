package wqs.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/** D2 / O-2: the two principal modes and the trusted header's parsing rules. */
@ExtendWith(OutputCaptureExtension.class)
class PrincipalResolverTest {

    private static final String HEADER = "X-Authenticated-User-Id";

    private static PrincipalResolver resolver(WebPrincipalProperties.Mode mode, String header) {
        return new PrincipalConfig().principalResolver(new WebPrincipalProperties(mode, header));
    }

    private static MockHttpServletRequest request(String header, String... values) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/web/v1/projection");
        for (String v : values) {
            r.addHeader(header, v);
        }
        return r;
    }

    @Test
    void defaults_are_none_and_the_gateway_header() {
        WebPrincipalProperties p = new WebPrincipalProperties(null, null);
        assertThat(p.mode()).isEqualTo(WebPrincipalProperties.Mode.NONE);
        assertThat(p.header()).isEqualTo(HEADER);
    }

    /** Only an UNSET header defaults; a set-but-unusable one fails (see ConfigBindingTest). */
    @Test
    void blank_or_non_token_header_is_rejected() {
        for (String bad : new String[] {"", "  ", "X User", "X-User:", "X-\u00dcser", "X,Y", " X-User"}) {
            assertThatThrownBy(() -> new WebPrincipalProperties(WebPrincipalProperties.Mode.TRUSTED_HEADER, bad))
                    .as("'%s'", bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void none_mode_never_resolves() {
        PrincipalResolver none = resolver(WebPrincipalProperties.Mode.NONE, null);
        assertThat(none.resolveUserId(request(HEADER, "1"))).isEmpty();
        assertThat(none.resolveUserId(request(HEADER))).isEmpty();
    }

    @Test
    void trusted_header_accepts_exactly_one_positive_decimal_long() {
        PrincipalResolver r = resolver(WebPrincipalProperties.Mode.TRUSTED_HEADER, null);
        assertThat(r.resolveUserId(request(HEADER, "1"))).isEqualTo(OptionalLong.of(1));
        assertThat(r.resolveUserId(request(HEADER, " 42\t"))).isEqualTo(OptionalLong.of(42));
        assertThat(r.resolveUserId(request(HEADER, String.valueOf(Long.MAX_VALUE))))
                .isEqualTo(OptionalLong.of(Long.MAX_VALUE));
        // Header names are case-insensitive (RFC 9110).
        assertThat(r.resolveUserId(request(HEADER.toLowerCase(), "7"))).isEqualTo(OptionalLong.of(7));
    }

    @Test
    void trusted_header_rejects_everything_else() {
        PrincipalResolver r = resolver(WebPrincipalProperties.Mode.TRUSTED_HEADER, null);
        assertThat(r.resolveUserId(request(HEADER))).as("absent").isEmpty();
        for (String bad : new String[] {"", " ", "0", "00", "-5", "+5", "-0", "abc", "1.0", "1e3", "0x10",
                "1,2", "1 2", "9223372036854775808", "99999999999999999999", "١", "１", "1\u0000"}) {
            assertThat(r.resolveUserId(request(HEADER, bad))).as("'%s'", bad).isEmpty();
        }
    }

    /** A gateway that appends rather than replaces would let a client inject the first value. */
    @Test
    void trusted_header_rejects_multiple_values() {
        PrincipalResolver r = resolver(WebPrincipalProperties.Mode.TRUSTED_HEADER, null);
        assertThat(r.resolveUserId(request(HEADER, "1", "2"))).isEmpty();
        assertThat(r.resolveUserId(request(HEADER, "1", "1"))).isEmpty();
        assertThat(r.resolveUserId(request(HEADER, "2", "abc"))).isEmpty();
    }

    @Test
    void trusted_header_reads_the_configured_header_only() {
        PrincipalResolver r = resolver(WebPrincipalProperties.Mode.TRUSTED_HEADER, "X-Gw-User");
        assertThat(r.resolveUserId(request("X-Gw-User", "5"))).isEqualTo(OptionalLong.of(5));
        assertThat(r.resolveUserId(request(HEADER, "5"))).isEmpty();
    }

    @Test
    void trusted_header_mode_warns_at_startup(CapturedOutput output) {
        resolver(WebPrincipalProperties.Mode.TRUSTED_HEADER, null);
        assertThat(output.getOut()).contains("WARN")
                .contains("trusted-header principal mode: the header MUST be set only by the gateway (O-2)");
    }

    @Test
    void none_mode_does_not_warn(CapturedOutput output) {
        resolver(WebPrincipalProperties.Mode.NONE, null);
        assertThat(output.getOut()).doesNotContain("trusted-header principal mode");
    }
}
