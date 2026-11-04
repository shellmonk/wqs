package wqs;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Base for tests that need the real infrastructure: one Postgres 16 and one RabbitMQ (streams
 * enabled) per test JVM, started on first use and shared by every subclass, so the Spring
 * context is cached across test classes too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    /**
     * A stream client connects to the address the broker advertises, not the one it dialled, so
     * the broker must advertise an address the test JVM can reach. A fixed host port (not 5552,
     * so a local `docker compose` broker does not clash) makes that address known before start.
     */
    protected static final int STREAM_PORT = 15552;

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    protected static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management")
            .withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stream]."), "/etc/rabbitmq/enabled_plugins")
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS",
                    "-rabbitmq_stream advertised_host localhost advertised_port " + STREAM_PORT)
            .withExposedPorts(5552)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(STREAM_PORT), new ExposedPort(5552))))
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wqs.stream.enabled", () -> "true");
        registry.add("wqs.stream.uri", () -> "rabbitmq-stream://localhost:" + STREAM_PORT);
    }
}
