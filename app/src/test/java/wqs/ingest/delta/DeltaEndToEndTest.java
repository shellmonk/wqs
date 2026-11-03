package wqs.ingest.delta;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static wqs.ingest.delta.DeltaRows.limit;
import static wqs.ingest.delta.DeltaRows.page;
import static wqs.ingest.delta.DeltaRows.payment;
import static wqs.ingest.delta.DeltaRows.prize;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;

/**
 * A real HTTP {@code /wallet/delta} (JDK HttpServer) -> the Spring-wired client and poller ->
 * projection tables -> /cc and /web responses: the polled sources' whole write/read loop.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class DeltaEndToEndTest extends PostgresTestBase {

    private static final FakeDeltaEndpoint ENDPOINT = new FakeDeltaEndpoint();
    private static final long USER = 5151L;
    private static final Instant T0 = Instant.parse("2034-11-04T10:00:00Z");
    private static final String FROM = "2034-11-01T00:00:00Z";
    private static final String TO = "2034-12-01T00:00:00Z";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("wqs.delta.base-url", ENDPOINT::baseUrl);
    }

    @AfterAll
    static void stopEndpoint() {
        ENDPOINT.close();
    }

    @Autowired private DeltaPoller poller;
    @Autowired private MockMvc mvc;
    @Autowired private PartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 2);
        clearProjection();
    }

    private static MockHttpServletRequestBuilder cc(int level) {
        return get("/cc/wallet-data/rest/v1/projection/{p}", USER).param("from", FROM).param("to", TO)
                .param("aggregationLevel", Integer.toString(level)).param("pageNumber", "1").param("pageSize", "10");
    }

    private static MockHttpServletRequestBuilder web(int level) {
        return get("/web/wallet-data/rest/v1/projection").header("X-Authenticated-User-Id", Long.toString(USER))
                .param("from", FROM).param("to", TO)
                .param("aggregationLevel", Integer.toString(level)).param("pageNumber", "1").param("pageSize", "10");
    }

    /** Review Focus 2: a newer version changes what /cc shows, and only that. */
    @Test
    void payment_status_change_reaches_cc_detail() throws Exception {
        ENDPOINT.enqueue(page(payment("900001", 1, USER, T0, T0.plusSeconds(60), 3)));
        poller.runOnce();
        mvc.perform(cc(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("900001"))
                .andExpect(jsonPath("$.data[0].detail.status").value("3"));

        ENDPOINT.enqueue(page(payment("900001", 1, USER, T0, T0.plusSeconds(120), 4)));
        poller.runOnce();
        mvc.perform(cc(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].txId").value("900001"))
                .andExpect(jsonPath("$.data[0].amount").value(-25.5))
                .andExpect(jsonPath("$.data[0].detail.status").value("4"));
    }

    @Test
    void claimable_prize_reaches_web() throws Exception {
        ENDPOINT.enqueue(page(
                prize("700001", USER, T0, T0.plusSeconds(60), "UNCLAIMED", "lotto", null),
                prize("700002", USER, T0.plusSeconds(3600), T0.plusSeconds(3660), "UNCLAIMED", "instants", "e2e-sess")));
        poller.runOnce();

        mvc.perform(web(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(2))
                .andExpect(jsonPath("$.data[*].txId", containsInAnyOrder("700001", "700002")))
                .andExpect(jsonPath("$.data[?(@.txId == '700001')].detail.status").value("UNCLAIMED"));
        mvc.perform(web(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("SESSION"))
                .andExpect(jsonPath("$.data[0].batchId").value("e2e-sess"))
                .andExpect(jsonPath("$.data[0].txs[*].txId", containsInAnyOrder("700002")))
                .andExpect(jsonPath("$.data[1].txId").value("700001"));
    }

    @Test
    void limit_without_currency_is_listed_on_cc() throws Exception {
        ENDPOINT.enqueue(page(limit("300001", USER, T0, T0.plusSeconds(5))));
        poller.runOnce();

        mvc.perform(cc(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("LIMIT"))
                .andExpect(jsonPath("$.data[0].txId").value("300001"))
                .andExpect(jsonPath("$.data[0].currency").value("XXX"))
                .andExpect(jsonPath("$.data[0].amount").value(0.0))
                .andExpect(jsonPath("$.data[0].detail.limitType").value("PLAY_COUNT"));
        mvc.perform(web(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRowCount").value(0));
    }
}
