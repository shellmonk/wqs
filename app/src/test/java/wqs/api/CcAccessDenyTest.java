package wqs.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * /cc is deny-by-default (wqs.cc.access, O-14): every request is 403 before validation or any
 * read. The other API tests run with access=open.
 */
@TestPropertySource(properties = "wqs.cc.access=deny")
class CcAccessDenyTest extends ApiTestBase {

    @Test
    void deny_mode_is_403_even_with_valid_params() throws Exception {
        mvc.perform(get("/cc/v1/projection").param("userId", "1").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("The /cc endpoint is disabled on this deployment"))
                .andExpect(jsonPath("$.instance").value("/cc/v1/projection"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    /** 403 comes before parameter binding and validation: a malformed request learns nothing. */
    @Test
    void deny_mode_is_403_before_validation() throws Exception {
        mvc.perform(get("/cc/v1/projection")).andExpect(status().isForbidden());
        mvc.perform(get("/cc/v1/projection;x=1").param("page", "0").param("from", "garbage"))
                .andExpect(status().isForbidden());
    }

    /** /web is governed by its principal mode, not by wqs.cc.access. */
    @Test
    void deny_mode_leaves_web_alone() throws Exception {
        mvc.perform(get("/web/v1/projection").header(PRINCIPAL_HEADER, "1").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk());
    }
}
