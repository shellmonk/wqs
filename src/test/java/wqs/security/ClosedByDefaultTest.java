package wqs.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import wqs.config.ApiAccessProperties;
import wqs.controller.CcProjectionController;
import wqs.controller.WebProjectionController;
import wqs.service.ProjectionService;

/** With the defaults (both switches off) neither endpoint serves anything, whatever the request carries. */
@WebMvcTest(controllers = {WebProjectionController.class, CcProjectionController.class})
@Import(HeaderOwnerIdResolver.class)
@EnableConfigurationProperties(ApiAccessProperties.class) // the slice skips @ConfigurationPropertiesScan
class ClosedByDefaultTest {

    static final String RANGE = "?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z";

    @Autowired MockMvc mockMvc;
    @MockitoBean ProjectionService projectionService;

    @Test
    void webIgnoresThePlayerHeaderByDefault() throws Exception {
        mockMvc.perform(get("/web/wallet-data/rest/v1/projection" + RANGE).header(HeaderOwnerIdResolver.HEADER, "1-1717"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(projectionService);
    }

    @Test
    void ccIsDeniedByDefault() throws Exception {
        mockMvc.perform(get("/cc/wallet-data/rest/v1/projection/1-1717" + RANGE)).andExpect(status().isForbidden());
        verifyNoInteractions(projectionService);
    }
}
