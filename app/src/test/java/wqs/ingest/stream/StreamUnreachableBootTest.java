package wqs.ingest.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import wqs.db.PostgresTestBase;

/**
 * D3: with the stream enabled and the broker unreachable at startup, the application still
 * starts and serves reads; the consumer keeps retrying in its recovery loop. No fake source: the
 * real (lazy) Environment points at a port nothing listens on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StreamUnreachableBootTest extends PostgresTestBase {

    private static final int CLOSED_PORT = closedPort();

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wqs.stream.enabled", () -> "true");
        registry.add("wqs.stream.uri", () -> "rabbitmq-stream://localhost:" + CLOSED_PORT);
        registry.add("wqs.stream.recovery-backoff", () -> "200ms");
    }

    @Autowired private MockMvc mvc;
    @Autowired private MeterRegistry meters;

    @Test
    void the_app_starts_and_serves_reads_while_the_broker_is_down() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/cc/v1/projection").param("userId", "1")
                        .param("from", "2034-02-01T00:00:00Z").param("to", "2034-03-01T00:00:00Z")
                        .param("aggregationLevel", "0").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk());
    }

    @Test
    void the_offset_lag_gauge_reads_nan_without_throwing() {
        double value = meters.get("wqs.ingest.stream.offset.lag").gauge().value();

        assertThat(value).isNaN();
    }
}
