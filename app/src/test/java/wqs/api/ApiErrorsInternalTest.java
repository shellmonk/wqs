package wqs.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import wqs.read.cc.CcProjectionService;

/** An unexpected failure is a fixed-text 500 ProblemDetail that reveals nothing about its cause. */
class ApiErrorsInternalTest extends ApiTestBase {

    @MockitoBean CcProjectionService failing;

    @Test
    void unexpected_exception_is_a_fixed_text_500() throws Exception {
        when(failing.level0(anyLong(), any(), any(), anyBoolean(), any(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("SECRET amount=12.50 userId=1 detail={}"));
        mvc.perform(get("/cc/v1/projection").param("userId", "1").param("from", FROM).param("to", TO)
                        .param("aggregationLevel", "0").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Internal error"))
                .andExpect(jsonPath("$.instance").value("/cc/v1/projection"))
                .andExpect(content().string(not(containsString("SECRET"))))
                .andExpect(content().string(not(containsString("IllegalState"))));
    }
}
