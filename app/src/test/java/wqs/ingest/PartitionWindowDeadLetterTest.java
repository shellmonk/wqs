package wqs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import wqs.db.PostgresTestBase;
import wqs.ingest.stream.FakeStreamSource;
import wqs.ingest.stream.StreamConsumer;
import wqs.ingest.stream.StreamOffsetStore;
import wqs.projection.source.StreamPayload;
import wqs.ingest.stream.StreamProperties;
import wqs.projection.ProjectionWriter;
import wqs.projection.StreamPayloadMapper;

/**
 * I1 / R100 / R103 with the production sanity window: an event 13+ months ahead, or before the
 * (here configured) provisioning floor, is poison -- dead-lettered, with no partition created.
 */
@SpringBootTest(properties = {
        "wqs.partition.max-months-ahead=12", "wqs.partition.provision-floor=2015-06"})
class PartitionWindowDeadLetterTest extends PostgresTestBase {

    private static final String STREAM = "wallet-transactions";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private DeadLetterStore deadLetterStore;
    @Autowired private StreamOffsetStore offsetStore;
    @Autowired private StreamPayloadMapper payloadMapper;
    @Autowired private wqs.db.TxTypeRegistry txTypes;
    @Autowired private ProjectionWriter writer;

    @BeforeEach
    void setUp() {
        clearProjection();
        db().execute("delete from \"wqDeadLetter\"");
    }

    @Test
    void an_event_more_than_twelve_months_ahead_is_dead_lettered_and_the_offset_advances() {
        FakeStreamSource source = new FakeStreamSource();
        StreamConsumer consumer = new StreamConsumer(
                source,
                new StreamProperties(true, STREAM, 1, Duration.ofMillis(1), 2, Duration.ofSeconds(5), null),
                payloadMapper, txTypes, writer, offsetStore, new SimpleMeterRegistry(), Clock.systemUTC(),
                deadLetterStore);
        consumer.start();
        // +14 rather than +13 so a month boundary passing mid-test cannot bring it into the window.
        Instant farAhead = YearMonth.now(ZoneOffset.UTC).plusMonths(14).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        source.queue(new StreamPayload("tx-garbage-date", 1, 7L, farAhead, "TT_BUY", "11", null, null, null, new BigDecimal("1.0000"), "EUR", null, null, java.util.Map.of()), 4);
        source.deliverAll();

        assertThat(db().row("select \"sourceId\", \"error\" from \"wqDeadLetter\"").asMap())
                .containsEntry("sourceId", "tx-garbage-date")
                .hasEntrySatisfying("error", e -> assertThat((String) e).contains("months ahead"));
        assertThat(offsetStore.read(STREAM)).contains(4L);
        assertThat(source.subscribeCount()).isEqualTo(1);
    }

    @Test
    void an_event_before_the_floor_is_dead_lettered_without_creating_any_partition() {
        FakeStreamSource source = new FakeStreamSource();
        StreamConsumer consumer = new StreamConsumer(
                source,
                new StreamProperties(true, STREAM, 1, Duration.ofMillis(1), 2, Duration.ofSeconds(5), null),
                payloadMapper, txTypes, writer, offsetStore, new SimpleMeterRegistry(), Clock.systemUTC(),
                deadLetterStore);
        consumer.start();
        int partitionsBefore = partitionCount();

        source.queue(new StreamPayload("tx-before-floor", 1, 7L, Instant.parse("2015-05-20T00:00:00Z"), "TT_BUY", "11", null, null, null, new BigDecimal("1.0000"), "EUR", null, null, java.util.Map.of()), 9);
        source.deliverAll();

        assertThat(db().row("select \"sourceId\", \"error\" from \"wqDeadLetter\"").asMap())
                .containsEntry("sourceId", "tx-before-floor")
                .hasEntrySatisfying("error", e -> assertThat((String) e).contains("2015-05").contains("floor"));
        assertThat(partitionCount()).isEqualTo(partitionsBefore);
        assertThat(offsetStore.read(STREAM)).contains(9L);
    }

    private static int partitionCount() {
        return db().value("select count(*)::int from pg_inherits", Integer.class);
    }
}
