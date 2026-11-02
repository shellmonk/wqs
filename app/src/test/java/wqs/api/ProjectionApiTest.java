package wqs.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;

/** Spec 7.1 / 7.9 request contract of the level-0 endpoints, and decisions D4-D6. */
class ProjectionApiTest extends ApiTestBase {

    @Autowired MeterRegistry registry;

    @BeforeEach
    void fixtures() {
        reset();
        project(
                item(1L, SourceKind.TRANSACTION, "t1", "2034-02-10T10:00:00Z"),
                item(1L, SourceKind.PAYMENT, "p1", "2034-02-11T10:00:00Z"),
                item(1L, SourceKind.LIMIT, "l1", "2034-02-12T10:00:00Z"));
    }

    /** A valid /cc level-0 request for user 1 over Feb 2034; override params per test. */
    private static MockHttpServletRequestBuilder cc(String... overrides) {
        MockHttpServletRequestBuilder b = get("/cc/v1/projection");
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of(
                "userId", "1", "from", FROM, "to", TO, "aggregationLevel", "0", "page", "1", "pageSize", "2"));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                params.remove(overrides[i]);
            } else {
                params.put(overrides[i], overrides[i + 1]);
            }
        }
        params.forEach(b::param);
        return b;
    }

    private ResultActions expectProblem(MockHttpServletRequestBuilder req, int status) throws Exception {
        return mvc.perform(req)
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void cc_level0_returns_envelope() throws Exception {
        mvc.perform(cc())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.aggregationLevel").value(0))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("LIMIT"))
                .andExpect(jsonPath("$.items[0].sourceId").value("l1"))
                // ISO-8601 instant string, not epoch numbers.
                .andExpect(jsonPath("$.items[0].occurredAt").value("2034-02-12T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].amount").value(12.5))
                .andExpect(jsonPath("$.items[0].detail", instanceOf(Map.class)))
                .andExpect(jsonPath("$.items[0].detail.note").value("l1"))
                // Internal columns stay internal.
                .andExpect(jsonPath("$.items[0].itemId").doesNotExist())
                .andExpect(jsonPath("$.items[0].ingestedAt").doesNotExist());
    }

    /** D4: valid but not implemented until Phase 5. */
    @Test
    void levels_1_and_2_are_501() throws Exception {
        expectProblem(cc("aggregationLevel", "1"), 501);
        expectProblem(cc("aggregationLevel", "2"), 501);
        expectProblem(web("aggregationLevel", "1"), 501);
        expectProblem(web("aggregationLevel", "2"), 501);
    }

    @Test
    void level_3_is_400() throws Exception {
        expectProblem(cc("aggregationLevel", "3"), 400).andExpect(jsonPath("$.detail", containsString("aggregationLevel")));
        expectProblem(cc("aggregationLevel", "-1"), 400);
        expectProblem(cc("aggregationLevel", "99999999999"), 400);
        expectProblem(cc("aggregationLevel", "x"), 400);
        expectProblem(web("aggregationLevel", "3"), 400);
    }

    /** D6. */
    @Test
    void from_after_to_is_400() throws Exception {
        expectProblem(cc("from", TO, "to", FROM), 400).andExpect(jsonPath("$.detail", containsString("from")));
        expectProblem(web("from", TO, "to", FROM), 400);
    }

    /** D6: the half-open interval [t, t) is empty, not an error. */
    @Test
    void from_equals_to_is_empty_200() throws Exception {
        mvc.perform(cc("from", "2034-02-10T10:00:00Z", "to", "2034-02-10T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    /**
     * C1 ruling: bounds are clamped into the data domain [1970, 2200) before any read, so an
     * expanded-year bound neither decomposes into millions of months nor reaches Postgres.
     */
    @Test
    void far_future_to_is_clamped_to_the_domain_end() throws Exception {
        for (boolean isWeb : new boolean[] {false, true}) {
            String clamped = mvc.perform(either(isWeb, "to", "2200-01-01T00:00:00Z"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            mvc.perform(either(isWeb, "to", "+1000000-01-01T00:00:00Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(isWeb ? 1 : 3))
                    .andExpect(content().json(clamped, true));
        }
    }

    @Test
    void far_past_from_is_clamped_to_the_domain_start() throws Exception {
        for (boolean isWeb : new boolean[] {false, true}) {
            for (String from : new String[] {"-0001-01-01T00:00:00Z", "0001-01-01T00:00:00Z"}) {
                mvc.perform(either(isWeb, "from", from))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalItems").value(isWeb ? 1 : 3))
                        .andExpect(jsonPath("$.items.length()").value(isWeb ? 1 : 2));
            }
        }
    }

    /** Both bounds beyond the same domain edge clamp to [edge, edge): empty, not an error. */
    @Test
    void both_bounds_beyond_the_domain_are_empty_200() throws Exception {
        String[][] ranges = {
            {"+300000-01-01T00:00:00Z", "+1000000-01-01T00:00:00Z"},
            {"-5000-01-01T00:00:00Z", "0001-01-01T00:00:00Z"},
        };
        for (boolean isWeb : new boolean[] {false, true}) {
            for (String[] r : ranges) {
                mvc.perform(either(isWeb, "from", r[0], "to", r[1]))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(0))
                        .andExpect(jsonPath("$.totalItems").value(0))
                        .andExpect(jsonPath("$.totalPages").value(0));
            }
        }
    }

    /** D6 is judged on the original bounds: inverted bounds beyond the domain are still 400. */
    @Test
    void inverted_bounds_beyond_the_domain_are_400() throws Exception {
        expectProblem(cc("from", "+1000000-01-01T00:00:00Z", "to", "+300000-01-01T00:00:00Z"), 400);
        expectProblem(web("from", "+1000000-01-01T00:00:00Z", "to", "+300000-01-01T00:00:00Z"), 400);
    }

    /**
     * M3: bounds are truncated to microseconds (Postgres' precision) before the read, so a
     * sub-microsecond bound answers exactly like its truncation, never like a DB-rounded value.
     * /cc sees all three fixtures; the l1 item sits exactly on the truncated instant.
     */
    @Test
    void sub_microsecond_bounds_are_truncated() throws Exception {
        String truncated = mvc.perform(cc("to", "2034-02-12T10:00:00Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(cc("to", "2034-02-12T10:00:00.000000600Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(truncated, true));
        mvc.perform(cc("from", "2034-02-12T10:00:00.000000600Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("l1"));
        // /web shares ProjectionParams; its one fixture (t1, 02-10T10:00) sits on a truncated `from`.
        mvc.perform(web("from", "2034-02-10T10:00:00.000000600Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("t1"));
    }

    private static MockHttpServletRequestBuilder either(boolean isWeb, String... overrides) {
        return isWeb ? web(overrides) : cc(overrides);
    }

    @Test
    void page_0_is_400() throws Exception {
        expectProblem(cc("page", "0"), 400).andExpect(jsonPath("$.detail", containsString("page")));
        expectProblem(cc("page", "-1"), 400);
        expectProblem(web("page", "0"), 400);
    }

    @Test
    void pageSize_0_is_400() throws Exception {
        expectProblem(cc("pageSize", "0"), 400).andExpect(jsonPath("$.detail", containsString("pageSize")));
        expectProblem(cc("pageSize", "-3"), 400);
        expectProblem(web("pageSize", "0"), 400);
    }

    /**
     * Ruling R2: the phase-4 integer productId filter is gone (products are strings since V10; the
     * contract's productTypes list filter arrives in phase 5 Task 7). The parameter is no longer
     * bound, so it neither filters nor fails.
     */
    @Test
    void productId_is_no_longer_a_filter() throws Exception {
        mvc.perform(cc("productId", "0")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(cc("productId", "11")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(web("productId", "11")).andExpect(status().isOk());
    }

    @Test
    void cc_without_userId_is_400() throws Exception {
        expectProblem(cc("userId", null), 400).andExpect(jsonPath("$.detail", containsString("userId")));
        expectProblem(cc("userId", "0"), 400);
        expectProblem(cc("userId", "-1"), 400);
        expectProblem(cc("userId", "abc"), 400);
    }

    @Test
    void missing_required_params_are_400() throws Exception {
        for (String p : new String[] {"from", "to", "aggregationLevel", "page", "pageSize"}) {
            expectProblem(cc(p, null), 400).andExpect(jsonPath("$.detail", containsString(p)));
            expectProblem(web(p, null), 400);
        }
    }

    @Test
    void bad_timestamp_is_400() throws Exception {
        String evil = "<script>alert(1)</script>";
        expectProblem(cc("from", evil), 400)
                .andExpect(jsonPath("$.detail", containsString("from")))
                // The client's input is never echoed back.
                .andExpect(content().string(not(containsString("script"))));
        expectProblem(cc("to", "2034-02-30T00:00:00Z"), 400);
        expectProblem(cc("from", "2034-02-01"), 400);
        // A local date-time without zone or offset is ambiguous, not UTC by assumption.
        expectProblem(cc("from", "2034-02-01T00:00:00"), 400);
        // Epoch millis are not ISO-8601.
        expectProblem(cc("from", "2000000000000"), 400);
        expectProblem(cc("pageSize", "1" + evil), 400).andExpect(content().string(not(containsString("script"))));
        expectProblem(cc("excludeCancelledTransactions", "maybe"), 400);
    }

    @Test
    void offset_timestamp_is_accepted() throws Exception {
        String z = mvc.perform(cc("to", "2034-02-12T10:00:00Z"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String offset = mvc.perform(cc("to", "2034-02-12T12:00:00+02:00"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(offset).isEqualTo(z).contains("\"totalItems\":2");
        mvc.perform(cc("from", "2034-02-01T02:00:00+02:00", "to", "2034-02-28T19:00:00-05:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    /** Spec 7.9: past the last page is an empty page with correct totals, not an error. */
    @Test
    void beyond_last_page_is_200_empty() throws Exception {
        mvc.perform(cc("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        // Review focus 3: page * pageSize overflow.
        mvc.perform(cc("page", String.valueOf(Long.MAX_VALUE), "pageSize", String.valueOf(Long.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void excludeCancelledTransactions_is_optional_and_bound() throws Exception {
        project(new wqs.domain.ProjectionItem(1L, SourceKind.TRANSACTION, "t1-cancel",
                java.time.Instant.parse("2034-02-13T10:00:00Z"), "P10", "keno", null, null,
                TxTypes.CANCEL,
                new java.math.BigDecimal("12.5000"), "EUR", null, "t1", null));
        mvc.perform(cc("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items[*].sourceId", hasItem("t1")));
        mvc.perform(cc("pageSize", "10", "excludeCancelledTransactions", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[*].sourceId", not(hasItem("t1"))));
    }

    /** RFC 9457 instance is the endpoint's path template, never the raw request URI. */
    @Test
    void problem_instance_is_the_endpoint_template() throws Exception {
        expectProblem(cc("page", "0"), 400).andExpect(jsonPath("$.instance").value("/cc/v1/projection"));
        expectProblem(web("page", "0"), 400).andExpect(jsonPath("$.instance").value("/web/v1/projection"));
        expectProblem(cc("aggregationLevel", "1"), 501).andExpect(jsonPath("$.instance").value("/cc/v1/projection"));
        String body = mvc.perform(get("/cc/v1/projection;x=<script>").param("page", "0"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("<script").doesNotContain("%3cscript").contains("\"/cc/v1/projection\"");
    }

    @Test
    void wrong_method_is_405_with_fixed_text() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/cc/v1/projection;x=<script>"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.detail").value("Method not allowed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Allow", containsString("GET")))
                .andExpect(content().string(not(containsString("script"))))
                .andExpect(content().string(not(containsString("POST"))));
    }

    /** The catch-all must not turn Spring's own client errors into 500s. */
    @Test
    void unknown_path_stays_404() throws Exception {
        mvc.perform(get("/nope/<script>"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("script"))));
    }

    /** Spec 11.2. The context's registry is shared by cached contexts' tests, so count the delta. */
    @Test
    void read_duration_is_tagged() throws Exception {
        long before = durationCount("cc", "0", "1");
        mvc.perform(cc()).andExpect(status().isOk());
        assertThat(durationCount("cc", "0", "1")).isEqualTo(before + 1);
        long deep = durationCount("cc", "0", "2-10");
        mvc.perform(cc("page", "5")).andExpect(status().isOk());
        assertThat(durationCount("cc", "0", "2-10")).isEqualTo(deep + 1);
        // 501s are not timed: they would seed the level 1/2 latency series with non-reads.
        long l1 = durationCount("cc", "1", "1");
        mvc.perform(cc("aggregationLevel", "1")).andExpect(status().isNotImplemented());
        assertThat(durationCount("cc", "1", "1")).isEqualTo(l1);
    }

    private long durationCount(String endpoint, String level, String bucket) {
        return registry.get("wqs.read.duration")
                .tag("endpoint", endpoint).tag("aggregationLevel", level).tag("pageDepthBucket", bucket)
                .timer().count();
    }

    private static MockHttpServletRequestBuilder web(String... overrides) {
        MockHttpServletRequestBuilder b = get("/web/v1/projection").header(PRINCIPAL_HEADER, "1");
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of(
                "from", FROM, "to", TO, "aggregationLevel", "0", "page", "1", "pageSize", "2"));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                params.remove(overrides[i]);
            } else {
                params.put(overrides[i], overrides[i + 1]);
            }
        }
        params.forEach(b::param);
        return b;
    }

    private void projectFilterFixtures() {
        project(
                new wqs.domain.ProjectionItem(1L, SourceKind.TRANSACTION, "x1", java.time.Instant.parse("2034-02-13T10:00:00Z"),
                        "idx1", "lottery", null, null, TxTypes.WIN, new java.math.BigDecimal("1.0000"), "EUR", null, null, null),
                new wqs.domain.ProjectionItem(1L, SourceKind.TRANSACTION, "x2", java.time.Instant.parse("2034-02-14T10:00:00Z"),
                        "idx2", "bingo", null, null, TxTypes.PLAY, new java.math.BigDecimal("1.0000"), "EUR", null, null, null));
    }

    /** D10: repeated and comma-separated forms bind alike; values are trimmed and blanks dropped. */
    @Test
    void list_filters_bind_repeated_and_comma_forms_trimmed_with_blanks_dropped() throws Exception {
        projectFilterFixtures();
        // cc sees all 5 items: keno x3 (t1, p1, l1), lottery x1, bingo x1.
        mvc.perform(cc().param("productTypes", "lottery", "bingo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(cc().param("productTypes", "lottery,bingo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(cc().param("productTypes", " lottery , bingo ,, "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        // Blank-only is "no filter" (blank is the counters' ALL sentinel), never "match blank".
        mvc.perform(cc().param("productTypes", " , "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(5));
        mvc.perform(cc().param("productTypes", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(5));
        mvc.perform(cc().param("transactionTypes", "PRIZE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("x1"))
                .andExpect(jsonPath("$.items[0].productType").value("lottery"))
                .andExpect(jsonPath("$.items[0].productId").value("idx1"))
                .andExpect(jsonPath("$.items[0].transactionType").value("PRIZE"));
        mvc.perform(cc().param("transactionTypes", "TT_BUY").param("productTypes", "bingo,lottery"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("x2"));
        mvc.perform(web().param("productTypes", "bingo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
    }

    /** An unknown value is not an error: it matches nothing. */
    @Test
    void unknown_list_filter_values_give_an_empty_200() throws Exception {
        projectFilterFixtures();
        mvc.perform(cc().param("transactionTypes", "TT_DOES_NOT_EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        mvc.perform(cc().param("productTypes", "nope"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        // One known code among unknown ones filters by the known one.
        mvc.perform(cc().param("transactionTypes", "TT_DOES_NOT_EXIST,PRIZE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
    }

    /** D10: at most 50 values per list. */
    @Test
    void more_than_fifty_values_in_a_list_is_400() throws Exception {
        for (String name : new String[] {"productTypes", "transactionTypes"}) {
            String fifty = java.util.stream.IntStream.range(0, 50).mapToObj(i -> "v" + i)
                    .collect(java.util.stream.Collectors.joining(","));
            mvc.perform(cc().param(name, fifty)).andExpect(status().isOk());
            expectProblem(cc().param(name, fifty + ",v50"), 400).andExpect(jsonPath("$.detail", containsString(name)));
            // repeated form counts the same
            MockHttpServletRequestBuilder repeated = cc();
            repeated.param(name, java.util.stream.IntStream.range(0, 51).mapToObj(i -> "r" + i).toArray(String[]::new));
            expectProblem(repeated, 400);
            expectProblem(web().param(name, fifty + ",v50"), 400);
        }
    }
}
