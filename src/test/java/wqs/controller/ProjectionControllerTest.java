package wqs.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import wqs.config.ApiAccessProperties;
import wqs.dto.AggregationLevel;
import wqs.dto.ProjectionRequest;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.security.HeaderOwnerIdResolver;
import wqs.service.ProjectionService;

/**
 * The HTTP layer with both endpoints switched on: identity, policy, parameter parsing and error
 * responses. The service is a mock; {@code ProjectionApiTest} runs the same endpoints end to end.
 */
@WebMvcTest(controllers = {WebProjectionController.class, CcProjectionController.class},
        properties = {"wqs.api.cc-enabled=true", "wqs.api.web-owner-header=true"})
@Import(HeaderOwnerIdResolver.class)
@EnableConfigurationProperties(ApiAccessProperties.class) // the slice skips @ConfigurationPropertiesScan
class ProjectionControllerTest {

    static final String WEB = "/web/wallet-data/rest/v1/projection";
    static final String CC = "/cc/wallet-data/rest/v1/projection/";
    static final String RANGE = "from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z";

    @Autowired MockMvc mockMvc;
    @MockitoBean ProjectionService projectionService;

    @Test
    void webServesTheHeadersOwnerUnderTheWebPolicy() throws Exception {
        doReturn(emptyPage()).when(projectionService).page(any(), any(), any());

        mockMvc.perform(get(WEB + "?" + RANGE).header(HeaderOwnerIdResolver.HEADER, "1-1717"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(0));

        verify(projectionService).page(eq(ReadPolicy.WEB), eq("1-1717"), any());
    }

    @Test
    void webWithoutAnOwnerIs401() throws Exception {
        mockMvc.perform(get(WEB + "?" + RANGE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(projectionService);
    }

    @Test
    void ccServesThePathsOwnerInItsCanonicalSpellingUnderTheCcPolicy() throws Exception {
        doReturn(emptyPage()).when(projectionService).page(any(), any(), any());

        mockMvc.perform(get(CC + "01-01717?" + RANGE)).andExpect(status().isOk());

        verify(projectionService).page(eq(ReadPolicy.CC), eq("1-1717"), any());
    }

    @Test
    void parametersBecomeTheRequestWithItsDefaults() throws Exception {
        doReturn(emptyPage()).when(projectionService).page(any(), any(), any());

        // A URI, not a template: get(String) would encode %2B (a "+" in the offset) a second time.
        mockMvc.perform(get(URI.create(CC + "1-1717?from=2026-10-01T02:00:00%2B02:00&to=2026-11-01T00:00:00Z"
                        + "&productTypes=instants,lotto&transactionTypes=TT_BUY&transactionTypes=PRIZE")))
                .andExpect(status().isOk());

        ArgumentCaptor<ProjectionRequest> request = ArgumentCaptor.forClass(ProjectionRequest.class);
        verify(projectionService).page(eq(ReadPolicy.CC), eq("1-1717"), request.capture());
        assertThat(request.getValue()).isEqualTo(new ProjectionRequest(
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
                AggregationLevel.TRANSACTIONS, false, Set.of("instants", "lotto"), Set.of("TT_BUY", "PRIZE"), 1, 50));
    }

    @Test
    void invalidParametersAre400NamingTheParameter() throws Exception {
        assertProblem(CC + "1-1717?to=2026-11-01T00:00:00Z", "from");
        assertProblem(CC + "1-1717?" + RANGE + "&aggregationLevel=3", "aggregationLevel");
        assertProblem(CC + "1-1717?" + RANGE + "&pageSize=1001", "pageSize");
        assertProblem(CC + "1-1717?" + RANGE + "&aggregationLevel=1&pageSize=101", "pageSize");
        assertProblem(CC + "1-1717?" + RANGE + "&pageNumber=0", "pageNumber");
        assertProblem(CC + "1-1717?from=yesterday&to=2026-11-01T00:00:00Z", "from");
        assertProblem(CC + "1-1717?from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z", "from");
        assertProblem(CC + "1717?" + RANGE, "ownerId");
        assertProblem(CC + "a-1717?" + RANGE, "ownerId");
        verifyNoInteractions(projectionService);
    }

    @Test
    void anErrorNeverEchoesTheValueSent() throws Exception {
        String body = mockMvc.perform(get(CC + "1-1717?from=EVIL-VALUE&to=2026-11-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("EVIL-VALUE");
    }

    private void assertProblem(String uri, String parameter) throws Exception {
        mockMvc.perform(get(uri))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value(parameter));
    }

    private static ProjectionResponse<Object> emptyPage() {
        return new ProjectionResponse<>(1, 0, 50, 0, List.of());
    }
}
