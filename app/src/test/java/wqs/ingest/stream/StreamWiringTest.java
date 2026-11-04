package wqs.ingest.stream;

import wqs.projection.source.StreamPayload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;

/**
 * Proves the stream consumer is actually wired into the running application: bean graph,
 * start-after-ApplicationReadyEvent, and the scheduled poll. A {@link FakeStreamSource} stands in
 * for the broker, so no {@code Environment} is ever built.
 */
@SpringBootTest
class StreamWiringTest extends PostgresTestBase {

    private static final String STREAM = "wallet-transactions";
    private static final Instant WHEN = Instant.parse("2033-09-02T12:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("wqs.stream.enabled", () -> "true");
    }

    @TestConfiguration
    static class FakeBroker {
        @Bean
        FakeStreamSource fakeStreamSource() {
            return new FakeStreamSource();
        }
    }

    /** The context starts the consumer, so the offset table must be empty before it does. */
    @BeforeAll
    static void emptyOffsetTable() {
        db().execute("delete from \"wqIngestCursor\"");
    }

    @Autowired private FakeStreamSource source;
    @Autowired private StreamOffsetStore offsetStore;
    @Autowired private PartitionManager partitionManager;
    @Autowired private ApplicationContext context;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2033, 9), 0);
        clearProjection();
    }

    @Test
    void the_consumer_is_started_polled_and_projects_into_the_transaction_tables() {
        assertThat(context.getBeanNamesForType(StreamConsumer.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(StreamDisabledNotice.class)).isEmpty();
        // Started by ApplicationReadyEvent, from offset 0 on an empty offset table.
        assertThat(source.subscribeCount()).isEqualTo(1);
        assertThat(source.subscribedFrom()).isZero();

        // One event never reaches the size threshold; only the scheduled poll can flush it.
        source.queue(new StreamPayload("tx-wired", 1, 7L, WHEN, "TT_BUY", "11", null, "b", "p", new BigDecimal("5.0000"), "EUR", null, null, java.util.Map.of()), 0);
        source.deliverAll();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(db().value("select count(*)::int from (select * from \"wqPrjTransaction\" where \"source\" in (1, 3)) w", Integer.class))
                    .isEqualTo(1);
            assertThat(offsetStore.read(STREAM)).contains(0L);
        });
    }
}
