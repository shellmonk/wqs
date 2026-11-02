package wqs.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import wqs.api.web.WebProjectionController;
import wqs.domain.SourceKind;

/**
 * Spec 9: {@code /web} serves the authenticated principal's history only. A client-supplied
 * {@code userId} is "the worst possible defect in this service", so these tests pin that the
 * parameter is ignored with a principal and that there is no answer but 401 without one.
 */
class WebProjectionSecurityTest extends ApiTestBase {

    @BeforeEach
    void fixtures() {
        reset();
        project(
                item(1L, SourceKind.TRANSACTION, "u1-tx", "2034-02-10T10:00:00Z"),
                item(1L, SourceKind.CLAIMABLE_PRIZE, "u1-prize", "2034-02-11T10:00:00Z"),
                item(1L, SourceKind.PAYMENT, "u1-payment", "2034-02-12T10:00:00Z"),
                item(2L, SourceKind.TRANSACTION, "u2-tx-a", "2034-02-10T11:00:00Z"),
                item(2L, SourceKind.TRANSACTION, "u2-tx-b", "2034-02-13T10:00:00Z"),
                item(2L, SourceKind.CLAIMABLE_PRIZE, "u2-prize", "2034-02-14T10:00:00Z"));
    }

    private static MockHttpServletRequestBuilder web() {
        return get("/web/v1/projection")
                .param("from", FROM).param("to", TO).param("aggregationLevel", "0")
                .param("page", "1").param("pageSize", "100");
    }

    /** Spec 9 control 2. */
    @Test
    void web_ignores_userId_parameter() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1").param("userId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sourceId", contains("u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
        // Repeated and differently-cased spellings change nothing either.
        mvc.perform(web().header(PRINCIPAL_HEADER, "1")
                        .param("userId", "2", "3").param("userid", "2").param("user_id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sourceId", contains("u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalItems").value(2));
        // And the same request as user 2 really does see user 2's (different) history.
        mvc.perform(web().header(PRINCIPAL_HEADER, "2").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sourceId", contains("u2-prize", "u2-tx-b", "u2-tx-a")))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void web_without_principal_is_401_even_with_userId() throws Exception {
        mvc.perform(web().param("userId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.totalItems").doesNotExist());
    }

    @Test
    void web_with_malformed_principal_is_401() throws Exception {
        for (String bad : List.of("abc", "0", "-5", "+5", "", " ", "1.0", "1e3", "0x10", "1,2", "1 2",
                "99999999999999999999", "١")) {
            mvc.perform(web().header(PRINCIPAL_HEADER, bad).param("userId", "1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.items").doesNotExist());
        }
    }

    /** A gateway that appends rather than replaces would otherwise let a client inject a value. */
    @Test
    void web_with_two_principal_headers_is_401() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1", "2"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.items").doesNotExist());
        mvc.perform(web().header(PRINCIPAL_HEADER, "1").header(PRINCIPAL_HEADER, "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void web_principal_is_trimmed() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "  1 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    /** Spec 9 control 3: operator-only sources never reach /web. */
    @Test
    void web_never_returns_cc_only_sources() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sourceId", not(hasItem("u1-payment"))))
                .andExpect(jsonPath("$.items[*].kind", not(hasItem(SourceKind.PAYMENT.name()))));
        mvc.perform(get("/cc/v1/projection")
                        .param("userId", "1").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("page", "1").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sourceId", contains("u1-payment", "u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    /**
     * Spec 9 control 1, as an ALLOWLIST: every parameter of every /web handler is either the raw
     * {@code HttpServletRequest} (handed only to the PrincipalResolver; ArchitectureTest bans its
     * parameter accessors) or an explicitly named {@code @RequestParam} of a simple type from the
     * fixed set below. Anything else -- an unannotated POJO (implicit {@code @ModelAttribute}), a
     * Map, a WebRequest, {@code @RequestHeader}, {@code @PathVariable}, {@code @MatrixVariable},
     * or a {@code @RequestParam} named userId -- fails.
     */
    @Test
    void web_controller_binds_only_allowlisted_parameters() {
        Set<String> allowedNames = Set.of(
                "from", "to", "aggregationLevel", "excludeCancelledTransactions", "page", "pageSize");
        Set<Class<?>> simpleTypes = Set.of(
                java.time.Instant.class, int.class, Integer.class, long.class, Long.class, boolean.class, Boolean.class);
        List<Method> handlers = new ArrayList<>();
        for (Method m : WebProjectionController.class.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) {
                handlers.add(m);
            }
        }
        assertThat(handlers).isNotEmpty();
        for (Method m : handlers) {
            for (Parameter p : m.getParameters()) {
                String where = m.getName() + "(" + p.getType().getSimpleName() + " " + p.getName() + ")";
                if (p.getType() == HttpServletRequest.class) {
                    assertThat(p.getAnnotations()).as(where).isEmpty();
                    continue;
                }
                RequestParam rp = p.getAnnotation(RequestParam.class);
                assertThat(rp).as("%s must be an explicit @RequestParam", where).isNotNull();
                assertThat(p.getAnnotations()).as("%s carries only @RequestParam/@DateTimeFormat", where)
                        .allMatch(an -> an instanceof RequestParam || an instanceof DateTimeFormat);
                assertThat(simpleTypes).as("%s is a simple type", where).contains(p.getType());
                // Explicit name (no reliance on -parameters), and both aliases agree.
                String name = rp.name().isEmpty() ? rp.value() : rp.name();
                assertThat(name).as("%s @RequestParam name", where).isIn(allowedNames);
                assertThat(rp.value().isEmpty() || rp.value().equals(name)).as(where).isTrue();
            }
        }
    }

    /** A ;matrix segment or encoded markup in the path is never echoed into the problem body. */
    @Test
    void problem_instance_never_echoes_the_request_uri() throws Exception {
        for (String path : List.of("/web/v1/projection;x=<script>", "/web/v1/projection;x=%3Cscript%3E")) {
            String body = mvc.perform(get(path).param("from", FROM).param("to", TO).param("aggregationLevel", "0")
                            .param("page", "1").param("pageSize", "1").param("userId", "1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.instance").value("/web/v1/projection"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body.toLowerCase()).doesNotContain("<script").doesNotContain("%3cscript").doesNotContain("x=");
        }
    }

    /** D2: the default mode answers every /web request with 401, whatever the headers say. */
    @Nested
    @TestPropertySource(properties = "wqs.web.principal.mode=none")
    class NoneMode {

        // The outer instance is injected from the outer (trusted-header) context; this one is
        // the none-mode context's.
        @Autowired MockMvc noneMvc;

        @Test
        void web_in_none_mode_is_always_401() throws Exception {
            noneMvc.perform(web().header(PRINCIPAL_HEADER, "1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.items").doesNotExist());
            noneMvc.perform(web().header(PRINCIPAL_HEADER, "1").param("userId", "1"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
