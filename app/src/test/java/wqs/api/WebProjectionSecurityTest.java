package wqs.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import wqs.api.web.archfixture.PlayerIdPathWebHandler;
import wqs.api.web.archfixture.UserIdParamWebHandler;
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
                transaction(1L, SourceKind.TRANSACTION, "u1-tx", "2034-02-10T10:00:00Z"),
                transaction(1L, SourceKind.CLAIMABLE_PRIZE, "u1-prize", "2034-02-11T10:00:00Z"),
                transaction(1L, SourceKind.PAYMENT, "u1-payment", "2034-02-12T10:00:00Z"),
                transaction(2L, SourceKind.TRANSACTION, "u2-tx-a", "2034-02-10T11:00:00Z"),
                transaction(2L, SourceKind.TRANSACTION, "u2-tx-b", "2034-02-13T10:00:00Z"),
                transaction(2L, SourceKind.CLAIMABLE_PRIZE, "u2-prize", "2034-02-14T10:00:00Z"));
    }

    private static MockHttpServletRequestBuilder web() {
        return get("/web/wallet-data/rest/v1/projection")
                .param("from", FROM).param("to", TO).param("aggregationLevel", "0")
                .param("pageNumber", "1").param("pageSize", "100");
    }

    /** Spec 9 control 2. */
    @Test
    void web_ignores_userId_parameter() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1").param("userId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].txId", contains("u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalRowCount").value(2))
                .andExpect(jsonPath("$.pagesAvailable").value(1));
        // Repeated and differently-cased spellings change nothing either.
        mvc.perform(web().header(PRINCIPAL_HEADER, "1")
                        .param("userId", "2", "3").param("userid", "2").param("user_id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].txId", contains("u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalRowCount").value(2));
        // And the same request as user 2 really does see user 2's (different) history.
        mvc.perform(web().header(PRINCIPAL_HEADER, "2").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].txId", contains("u2-prize", "u2-tx-b", "u2-tx-a")))
                .andExpect(jsonPath("$.totalRowCount").value(3));
    }

    @Test
    void web_without_principal_is_401_even_with_userId() throws Exception {
        mvc.perform(web().param("userId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.totalRowCount").doesNotExist());
    }

    @Test
    void web_with_malformed_principal_is_401() throws Exception {
        for (String bad : List.of("abc", "0", "-5", "+5", "", " ", "1.0", "1e3", "0x10", "1,2", "1 2",
                "99999999999999999999", "١")) {
            mvc.perform(web().header(PRINCIPAL_HEADER, bad).param("userId", "1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    /** A gateway that appends rather than replaces would otherwise let a client inject a value. */
    @Test
    void web_with_two_principal_headers_is_401() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1", "2"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(web().header(PRINCIPAL_HEADER, "1").header(PRINCIPAL_HEADER, "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void web_principal_is_trimmed() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "  1 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(2));
    }

    /** Spec 9 control 3: operator-only sources never reach /web. */
    @Test
    void web_never_returns_cc_only_sources() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].txId", not(hasItem("u1-payment"))))
                .andExpect(jsonPath("$.data[*].kind", not(hasItem(SourceKind.PAYMENT.name()))));
        mvc.perform(get("/cc/wallet-data/rest/v1/projection/1")
                        .param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("pageNumber", "1").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].txId", contains("u1-payment", "u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalRowCount").value(3));
    }

    /**
     * Spec 9 control 3 on the single transaction table: every source is stored once in
     * "wqPrjTransaction", and no stored row knows an endpoint, so /web's isolation rests on its read
     * policy (plan D1) bound into every query. A payment is never a /web row, never a /web entry,
     * never a nested /web transaction and never in a /web total. A payment carrying a batch is stored
     * and sessioned like any transaction (the ingest rule is gone): its session belongs to the policies
     * of its first source, so it is a /cc entry and never a /web one. A session of /web sources --
     * here one whose only transaction is cancelled -- looks the same on both endpoints.
     */
    @Test
    void a_payment_never_reaches_web_through_the_policy_the_entries_or_the_totals() throws Exception {
        writer.apply(new wqs.projection.ProjectionBatch("wallet-transactions", List.of(
                sessioned(SourceKind.TRANSACTION, "u1-s-tx", "2034-02-15T10:00:00Z", "s-web", null),
                sessioned(SourceKind.PAYMENT, "u1-pay2", "2034-02-16T10:00:00Z", null, null),
                sessioned(SourceKind.TRANSACTION, "u1-s-c", "2034-02-17T10:00:00Z", null, "u1-s-tx")), 2L));
        var sessionedPayment = writer.apply(new wqs.projection.ProjectionBatch("wallet-transactions", List.of(
                sessioned(SourceKind.PAYMENT, "u1-s-pay", "2034-02-16T11:00:00Z", "s-pay", null)), 3L));
        assertThat(sessionedPayment.projected()).as("a sessioned payment is stored like any row").isEqualTo(1);
        assertThat(sessionedPayment.deadLettered()).isZero();
        List<String> payments = List.of("u1-payment", "u1-pay2", "u1-s-pay");

        assertThat(db().column("select \"sourceId\" from \"wqPrjTransaction\" where \"source\" = ?", String.class,
                (short) SourceKind.PAYMENT.code())).as("stored once, in the one table")
                .containsExactlyInAnyOrderElementsOf(payments);
        assertThat(db().value("select \"firstSource\" from \"wqPrjSession\" where \"batchId\" = 's-pay'", Short.class))
                .as("the session of a payment").isEqualTo((short) SourceKind.PAYMENT.code());

        for (String level : List.of("0", "1", "2")) {
            for (String excl : List.of("false", "true")) {
                var page = mvc.perform(webAt(level, excl).header(PRINCIPAL_HEADER, "1")).andExpect(status().isOk());
                for (String payment : payments) {
                    page.andExpect(jsonPath("$..txId", not(hasItem(payment))));
                }
                page.andExpect(jsonPath("$..kind", not(hasItem(SourceKind.PAYMENT.name()))));
                page.andExpect(jsonPath("$..batchId", not(hasItem("s-pay"))));
            }
        }
        // L0 totals: u1-tx, u1-prize, u1-s-tx, u1-s-c; excluding cancelled drops u1-s-tx.
        mvc.perform(webAt("0", "false").header(PRINCIPAL_HEADER, "1"))
                .andExpect(jsonPath("$.totalRowCount").value(4));
        mvc.perform(webAt("0", "true").header(PRINCIPAL_HEADER, "1"))
                .andExpect(jsonPath("$.totalRowCount").value(3));
        // Entry paging and totals: u1-tx, u1-prize and s-web; the payments (and their session) are /cc only.
        for (String level : List.of("1", "2")) {
            mvc.perform(webAt(level, "false").header(PRINCIPAL_HEADER, "1"))
                    .andExpect(jsonPath("$.totalRowCount").value(3))
                    .andExpect(jsonPath("$.data[*].batchId", hasItem("s-web")));
            mvc.perform(webAt(level, "true").header(PRINCIPAL_HEADER, "1"))
                    .andExpect(jsonPath("$.totalRowCount").value(2))
                    .andExpect(jsonPath("$.data[*].txId", contains("u1-prize", "u1-tx")));
            mvc.perform(cc(level, "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRowCount").value(6))
                    .andExpect(jsonPath("$.data[*].batchId", hasItem("s-web")))
                    .andExpect(jsonPath("$.data[*].batchId", hasItem("s-pay")));
            // The emptied session is hidden on /cc exactly as on /web.
            mvc.perform(cc(level, "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRowCount").value(5))
                    .andExpect(jsonPath("$.data[*].batchId", not(hasItem("s-web"))))
                    .andExpect(jsonPath("$.data[*].txId", hasItem("u1-pay2")));
        }
    }

    private static MockHttpServletRequestBuilder cc(String level, String excludeCancelled) {
        return get("/cc/wallet-data/rest/v1/projection/1")
                .param("from", FROM).param("to", TO).param("aggregationLevel", level)
                .param("excludeCancelledTransactions", excludeCancelled)
                .param("pageNumber", "1").param("pageSize", "100");
    }

    private static MockHttpServletRequestBuilder webAt(String level, String excludeCancelled) {
        return get("/web/wallet-data/rest/v1/projection")
                .param("from", FROM).param("to", TO).param("aggregationLevel", level)
                .param("excludeCancelledTransactions", excludeCancelled)
                .param("pageNumber", "1").param("pageSize", "100");
    }

    private static wqs.domain.ProjectionTransaction sessioned(
            SourceKind source, String sourceId, String at, String batchId, String cancels) {
        return new wqs.domain.ProjectionTransaction(1L, source, sourceId, java.time.Instant.parse(at), "P10", "keno",
                batchId, null, cancels == null ? wqs.gen.TxTypes.PLAY : wqs.gen.TxTypes.CANCEL,
                new java.math.BigDecimal(cancels == null ? "12.5000" : "-12.5000"), "EUR", null, cancels, null);
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
        assertOnlyAllowlistedParameters(WebProjectionController.class);
    }

    /** The allowlist can fail: a /web handler with @RequestParam userId or @PathVariable playerId is rejected. */
    @Test
    void allowlist_rejects_userId_request_param_and_playerId_path_variable() {
        assertThatThrownBy(() -> assertOnlyAllowlistedParameters(UserIdParamWebHandler.class))
                .isInstanceOf(AssertionError.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> assertOnlyAllowlistedParameters(PlayerIdPathWebHandler.class))
                .isInstanceOf(AssertionError.class).hasMessageContaining("explicit @RequestParam");
    }

    /** /web has no player path variable: the user is the principal, whatever the path or query says. */
    @Test
    void web_ignores_playerId_and_userId_params() throws Exception {
        mvc.perform(web().header(PRINCIPAL_HEADER, "1").param("playerId", "2").param("userId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].txId", contains("u1-prize", "u1-tx")))
                .andExpect(jsonPath("$.totalRowCount").value(2));
        // A path-variable form does not exist on /web.
        mvc.perform(get("/web/wallet-data/rest/v1/projection/2").header(PRINCIPAL_HEADER, "1")
                        .param("from", FROM).param("to", TO).param("aggregationLevel", "0")
                        .param("pageNumber", "1").param("pageSize", "100"))
                .andExpect(status().isNotFound());
    }

    private static void assertOnlyAllowlistedParameters(Class<?> controller) {
        Set<String> allowedNames = Set.of(
                "from", "to", "aggregationLevel", "excludeCancelledTransactions", "productTypes", "transactionTypes",
                "pageNumber", "pageSize");
        Set<Class<?>> simpleTypes = Set.of(
                java.time.Instant.class, int.class, Integer.class, long.class, Long.class, boolean.class, Boolean.class,
                // the two D10 list filters, List<String> (checked below)
                List.class);
        List<Method> handlers = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
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
                if (p.getType() == List.class) {
                    assertThat(p.getParameterizedType().getTypeName()).as(where).isEqualTo("java.util.List<java.lang.String>");
                }
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
        for (String path : List.of("/web/wallet-data/rest/v1/projection;x=<script>", "/web/wallet-data/rest/v1/projection;x=%3Cscript%3E")) {
            String body = mvc.perform(get(path).param("from", FROM).param("to", TO).param("aggregationLevel", "0")
                            .param("pageNumber", "1").param("pageSize", "1").param("userId", "1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.instance").value("/web/wallet-data/rest/v1/projection"))
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
                    .andExpect(jsonPath("$.data").doesNotExist());
            noneMvc.perform(web().header(PRINCIPAL_HEADER, "1").param("userId", "1"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
