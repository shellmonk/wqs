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

    /** D5: 0 is the counter sentinel, never a real product. */
    @Test
    void productId_0_is_400() throws Exception {
        expectProblem(cc("productId", "0"), 400).andExpect(jsonPath("$.detail", containsString("productId")));
        expectProblem(cc("productId", "-1"), 400);
        expectProblem(web("productId", "0"), 400);
        mvc.perform(cc("productId", "10")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(cc("productId", "11")).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
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
                java.time.Instant.parse("2034-02-13T10:00:00Z"), 10, null, null, (short) 1,
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
}
