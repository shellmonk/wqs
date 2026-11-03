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
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of(
                "playerId", "1", "from", FROM, "to", TO, "aggregationLevel", "0", "pageNumber", "1", "pageSize", "2"));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                params.remove(overrides[i]);
            } else {
                params.put(overrides[i], overrides[i + 1]);
            }
        }
        MockHttpServletRequestBuilder b = get("/cc/wallet-data/rest/v1/projection/{playerId}", params.remove("playerId"));
        params.forEach(b::param);
        return b;
    }

    private ResultActions expectProblem(MockHttpServletRequestBuilder req, int status) throws Exception {
        return mvc.perform(req)
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void cc_level0_returns_envelope() throws Exception {
        mvc.perform(cc())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalRowCount").value(3))
                .andExpect(jsonPath("$.pagesAvailable").value(2))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("LIMIT"))
                .andExpect(jsonPath("$.data[0].txId").value("l1"))
                // ISO-8601 instant string, not epoch numbers.
                .andExpect(jsonPath("$.data[0].created").value("2034-02-12T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].amount").value(12.5))
                .andExpect(jsonPath("$.data[0].detail", instanceOf(Map.class)))
                .andExpect(jsonPath("$.data[0].detail.note").value("l1"))
                // Internal columns stay internal.
                .andExpect(jsonPath("$.data[0].itemId").doesNotExist())
                .andExpect(jsonPath("$.data[0].ingestedAt").doesNotExist());
    }

    /** Phase 5 Task 8: levels 1 and 2 are served (no longer D4's 501), sessions nested per level. */
    @Test
    void levels_1_and_2_no_longer_501() throws Exception {
        project(session("s1-a", "2034-02-13T10:00:00Z", "s1-p1"), session("s1-b", "2034-02-13T10:05:00Z", null));
        for (String level : new String[] {"1", "2"}) {
            mvc.perform(cc("aggregationLevel", level, "pageSize", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageNumber").value(1))
                    .andExpect(jsonPath("$.pageSize").value(10))
                    .andExpect(jsonPath("$.totalRowCount").value(4))
                    .andExpect(jsonPath("$.pagesAvailable").value(1))
                    .andExpect(jsonPath("$.data.length()").value(4))
                    .andExpect(jsonPath("$.data[0].kind").value("SESSION"))
                    .andExpect(jsonPath("$.data[0].batchId").value("s1"))
                    .andExpect(jsonPath("$.data[0].created").value("2034-02-13T10:00:00Z"))
                    .andExpect(jsonPath("$.data[0].totalPlay").value(25.0))
                    .andExpect(jsonPath("$.data[0].totalWin").value(0.0))
                    .andExpect(jsonPath("$.data[0].amount").value(-25.0))
                    .andExpect(jsonPath("$.data[0].status").doesNotExist())
                    .andExpect(jsonPath("$.data[1].kind").value("LIMIT"))
                    .andExpect(jsonPath("$.data[1].txId").value("l1"));
            mvc.perform(web("aggregationLevel", level, "pageSize", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRowCount").value(2))
                    .andExpect(jsonPath("$.data[0].kind").value("SESSION"))
                    .andExpect(jsonPath("$.data[1].txId").value("t1"));
        }
        mvc.perform(cc("aggregationLevel", "1", "pageSize", "10"))
                .andExpect(jsonPath("$.data[0].txs[*].txId", org.hamcrest.Matchers.contains("s1-b", "s1-a")))
                .andExpect(jsonPath("$.data[0].subitems").doesNotExist());
        mvc.perform(cc("aggregationLevel", "2", "pageSize", "10"))
                .andExpect(jsonPath("$.data[0].subitems[0].subBatchId").value("s1-p1"))
                .andExpect(jsonPath("$.data[0].subitems[0].playTimestamp").value("2034-02-13T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].subitems[0].playAmount").value(12.5))
                .andExpect(jsonPath("$.data[0].subitems[0].txs[*].txId", org.hamcrest.Matchers.contains("s1-a")))
                // the play-less item stays on the session at level 2
                .andExpect(jsonPath("$.data[0].txs[*].txId", org.hamcrest.Matchers.contains("s1-b")));
    }

    private static wqs.domain.ProjectionItem session(String sourceId, String at, String subBatchId) {
        return new wqs.domain.ProjectionItem(1L, SourceKind.TRANSACTION, sourceId, java.time.Instant.parse(at), "P10",
                "keno", "s1", subBatchId, TxTypes.PLAY, new java.math.BigDecimal("12.5000"), "EUR", null, null, null);
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
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.totalRowCount").value(0))
                .andExpect(jsonPath("$.pagesAvailable").value(0));
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
                    .andExpect(jsonPath("$.totalRowCount").value(isWeb ? 1 : 3))
                    .andExpect(content().json(clamped, true));
        }
    }

    @Test
    void far_past_from_is_clamped_to_the_domain_start() throws Exception {
        for (boolean isWeb : new boolean[] {false, true}) {
            for (String from : new String[] {"-0001-01-01T00:00:00Z", "0001-01-01T00:00:00Z"}) {
                mvc.perform(either(isWeb, "from", from))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalRowCount").value(isWeb ? 1 : 3))
                        .andExpect(jsonPath("$.data.length()").value(isWeb ? 1 : 2));
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
                        .andExpect(jsonPath("$.data.length()").value(0))
                        .andExpect(jsonPath("$.totalRowCount").value(0))
                        .andExpect(jsonPath("$.pagesAvailable").value(0));
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
                .andExpect(jsonPath("$.totalRowCount").value(2))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(cc("to", "2034-02-12T10:00:00.000000600Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(truncated, true));
        mvc.perform(cc("from", "2034-02-12T10:00:00.000000600Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("l1"));
        // /web shares ProjectionParams; its one fixture (t1, 02-10T10:00) sits on a truncated `from`.
        mvc.perform(web("from", "2034-02-10T10:00:00.000000600Z", "pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("t1"));
    }

    private static MockHttpServletRequestBuilder either(boolean isWeb, String... overrides) {
        return isWeb ? web(overrides) : cc(overrides);
    }

    @Test
    void page_0_is_400() throws Exception {
        expectProblem(cc("pageNumber", "0"), 400).andExpect(jsonPath("$.detail", containsString("pageNumber")));
        expectProblem(cc("pageNumber", "-1"), 400);
        expectProblem(web("pageNumber", "0"), 400);
    }

    @Test
    void pageSize_0_is_400() throws Exception {
        expectProblem(cc("pageSize", "0"), 400).andExpect(jsonPath("$.detail", containsString("pageSize")));
        expectProblem(cc("pageSize", "-3"), 400);
        expectProblem(web("pageSize", "0"), 400);
    }

    /**
     * Ruling R2: the phase-4 integer productId filter is gone (products are strings, not ids; the
     * contract's productTypes list filter arrives in phase 5 Task 7). The parameter is no longer
     * bound, so it neither filters nor fails.
     */
    @Test
    void productId_is_no_longer_a_filter() throws Exception {
        mvc.perform(cc("productId", "0")).andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(3));
        mvc.perform(cc("productId", "11")).andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(3));
        mvc.perform(web("productId", "11")).andExpect(status().isOk());
    }

    @Test
    void cc_player_id_must_be_positive_long() throws Exception {
        for (String bad : new String[] {"abc", "0", "-1", "+1", "9223372036854775808", "1.0", "0x10", "1e3",
                "00", "\u0663", "99999999999999999999999999999999999999999999999"}) {
            expectProblem(cc("playerId", bad), 400).andExpect(jsonPath("$.detail", containsString("playerId")));
        }
        mvc.perform(cc("playerId", "9223372036854775807")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(0));
        // phase 4 accepted a leading zero in userId; so does the path form
        mvc.perform(cc("playerId", "01")).andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(3));
        // the player id is required: no segment, no route
        mvc.perform(get("/cc/wallet-data/rest/v1/projection").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("pageNumber", "1").param("pageSize", "2"))
                .andExpect(status().isNotFound());
    }

    /** Task 7 step 1: page 1 is the newest page, and the numbering is 1-based. */
    @Test
    void pageNumber_is_one_based() throws Exception {
        mvc.perform(cc("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("l1"));
        mvc.perform(cc("pageSize", "1", "pageNumber", "3"))
                .andExpect(jsonPath("$.pageNumber").value(3))
                .andExpect(jsonPath("$.data[0].txId").value("t1"));
        mvc.perform(web("pageSize", "1"))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("t1"));
    }

    @Test
    void pageNumber_zero_is_400() throws Exception {
        expectProblem(cc("pageNumber", "0"), 400).andExpect(jsonPath("$.parameter").value("pageNumber"));
        expectProblem(web("pageNumber", "0"), 400);
        expectProblem(cc("pageNumber", "-1"), 400);
        // the phase-4 name is not the contract's: `page` alone no longer satisfies the endpoint
        expectProblem(cc("pageNumber", null).param("page", "1"), 400);
    }

    /** The contract's field names, exactly, in the envelope and on a transaction. */
    @Test
    void envelope_field_names_match_contract() throws Exception {
        String body = mvc.perform(cc("pageSize", "10")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        java.util.List<String> names = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("pageNumber", "pagesAvailable", "pageSize", "totalRowCount", "data");
        java.util.List<String> tx = new java.util.ArrayList<>();
        root.get("data").get(0).fieldNames().forEachRemaining(tx::add);
        // null fields (batchId, subBatchId, balanceAfter) are omitted; `status` never appears (D12)
        assertThat(tx).containsExactlyInAnyOrder("txId", "kind", "created", "productId", "productType",
                "transactionType", "amount", "currency", "cancelled", "detail");
        mvc.perform(cc("pageSize", "10"))
                .andExpect(jsonPath("$.data[0].kind").value("LIMIT"))
                .andExpect(jsonPath("$.data[0].created").value("2034-02-12T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].productId").value("P10"))
                .andExpect(jsonPath("$.data[0].productType").value("keno"))
                .andExpect(jsonPath("$.data[0].cancelled").value(false))
                .andExpect(jsonPath("$.data[0].status").doesNotExist())
                .andExpect(jsonPath("$.data[0].sourceId").doesNotExist())
                .andExpect(jsonPath("$.data[0].detail.note").value("l1"));
    }

    /** The phase-4 paths are gone. */
    @Test
    void old_paths_are_404() throws Exception {
        mvc.perform(get("/web/v1/projection").header(PRINCIPAL_HEADER, "1").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("pageNumber", "1").param("pageSize", "2"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/cc/v1/projection").param("userId", "1").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("pageNumber", "1").param("pageSize", "2"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/cc/v1/projection/1")).andExpect(status().isNotFound());
    }

    @Test
    void pagesAvailable_is_ceil_of_total_over_effective_page_size() throws Exception {
        mvc.perform(cc("pageSize", "2")).andExpect(jsonPath("$.pagesAvailable").value(2));
        mvc.perform(cc("pageSize", "3")).andExpect(jsonPath("$.pagesAvailable").value(1));
        mvc.perform(cc("pageSize", "1")).andExpect(jsonPath("$.pagesAvailable").value(3));
        mvc.perform(cc("playerId", "7")).andExpect(jsonPath("$.pagesAvailable").value(0))
                .andExpect(jsonPath("$.totalRowCount").value(0)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void missing_required_params_are_400() throws Exception {
        for (String p : new String[] {"from", "to", "aggregationLevel", "pageNumber", "pageSize"}) {
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
        assertThat(offset).isEqualTo(z).contains("\"totalRowCount\":2");
        mvc.perform(cc("from", "2034-02-01T02:00:00+02:00", "to", "2034-02-28T19:00:00-05:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(3));
    }

    /** Spec 7.9: past the last page is an empty page with correct totals, not an error. */
    @Test
    void beyond_last_page_is_200_empty() throws Exception {
        mvc.perform(cc("pageNumber", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value(3))
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.totalRowCount").value(3))
                .andExpect(jsonPath("$.pagesAvailable").value(2));
        mvc.perform(cc("pageNumber", "2147483647", "pageSize", "2147483647"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.totalRowCount").value(3))
                .andExpect(jsonPath("$.pagesAvailable").value(1));
    }

    @Test
    void excludeCancelledTransactions_is_optional_and_bound() throws Exception {
        project(new wqs.domain.ProjectionItem(1L, SourceKind.TRANSACTION, "t1-cancel",
                java.time.Instant.parse("2034-02-13T10:00:00Z"), "P10", "keno", null, null,
                TxTypes.CANCEL,
                new java.math.BigDecimal("12.5000"), "EUR", null, "t1", null));
        mvc.perform(cc("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(4))
                .andExpect(jsonPath("$.data[*].txId", hasItem("t1")));
        mvc.perform(cc("pageSize", "10", "excludeCancelledTransactions", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(3))
                .andExpect(jsonPath("$.data[*].txId", not(hasItem("t1"))));
    }

    /** RFC 9457 instance is the endpoint's path template, never the raw request URI. */
    @Test
    void problem_instance_is_the_endpoint_template() throws Exception {
        expectProblem(cc("pageNumber", "0"), 400).andExpect(jsonPath("$.instance").value("/cc/wallet-data/rest/v1/projection/%7BplayerId%7D"));
        expectProblem(web("pageNumber", "0"), 400).andExpect(jsonPath("$.instance").value("/web/wallet-data/rest/v1/projection"));
        expectProblem(cc("aggregationLevel", "1", "pageNumber", "0"), 400).andExpect(jsonPath("$.instance").value("/cc/wallet-data/rest/v1/projection/%7BplayerId%7D"));
        String body = mvc.perform(get("/cc/wallet-data/rest/v1/projection/1;x=<script>").param("pageNumber", "0"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("<script").doesNotContain("%3cscript").contains("\"/cc/wallet-data/rest/v1/projection/%7bplayerid%7d\"");
    }

    @Test
    void wrong_method_is_405_with_fixed_text() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/cc/wallet-data/rest/v1/projection/1;x=<script>"))
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
        mvc.perform(cc("pageNumber", "5")).andExpect(status().isOk());
        assertThat(durationCount("cc", "0", "2-10")).isEqualTo(deep + 1);
        // Levels 1 and 2 are timed under their own level tag.
        long l1 = durationCount("cc", "1", "1");
        mvc.perform(cc("aggregationLevel", "1")).andExpect(status().isOk());
        assertThat(durationCount("cc", "1", "1")).isEqualTo(l1 + 1);
        long w2 = durationCount("web", "2", "1");
        mvc.perform(web("aggregationLevel", "2")).andExpect(status().isOk());
        assertThat(durationCount("web", "2", "1")).isEqualTo(w2 + 1);
    }

    private long durationCount(String endpoint, String level, String bucket) {
        return registry.get("wqs.read.duration")
                .tag("endpoint", endpoint).tag("aggregationLevel", level).tag("pageDepthBucket", bucket)
                .timer().count();
    }

    private static MockHttpServletRequestBuilder web(String... overrides) {
        MockHttpServletRequestBuilder b = get("/web/wallet-data/rest/v1/projection").header(PRINCIPAL_HEADER, "1");
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of(
                "from", FROM, "to", TO, "aggregationLevel", "0", "pageNumber", "1", "pageSize", "2"));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(2));
        mvc.perform(cc().param("productTypes", "lottery,bingo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(2));
        mvc.perform(cc().param("productTypes", " lottery , bingo ,, "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(2));
        // Blank-only is "no filter" (blank is the counters' ALL sentinel), never "match blank".
        mvc.perform(cc().param("productTypes", " , "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(5));
        mvc.perform(cc().param("productTypes", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(5));
        mvc.perform(cc().param("transactionTypes", "PRIZE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("x1"))
                .andExpect(jsonPath("$.data[0].productType").value("lottery"))
                .andExpect(jsonPath("$.data[0].productId").value("idx1"))
                .andExpect(jsonPath("$.data[0].transactionType").value("PRIZE"));
        mvc.perform(cc().param("transactionTypes", "TT_BUY").param("productTypes", "bingo,lottery"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("x2"));
        mvc.perform(web().param("productTypes", "bingo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(1));
    }

    /** An unknown value is not an error: it matches nothing. */
    @Test
    void unknown_list_filter_values_give_an_empty_200() throws Exception {
        projectFilterFixtures();
        mvc.perform(cc().param("transactionTypes", "TT_DOES_NOT_EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.totalRowCount").value(0))
                .andExpect(jsonPath("$.pagesAvailable").value(0));
        mvc.perform(cc().param("productTypes", "nope"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(0));
        // One known code among unknown ones filters by the known one.
        mvc.perform(cc().param("transactionTypes", "TT_DOES_NOT_EXIST,PRIZE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRowCount").value(1));
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
