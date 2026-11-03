package wqs.ingest.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static wqs.ingest.delta.DeltaRows.limit;
import static wqs.ingest.delta.DeltaRows.page;
import static wqs.ingest.delta.DeltaRows.payment;
import static wqs.ingest.delta.DeltaRows.prize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.db.TxTypeRegistry;
import wqs.domain.SourceKind;
import wqs.ingest.DeadLetterStore;
import wqs.projection.DeltaWatermarkStore;
import wqs.projection.ProjectionSnapshot;
import wqs.projection.ProjectionWriter;

/**
 * One {@link DeltaPoller#runOnce} against a faked {@code /wallet/delta} (MockRestServiceServer) and
 * the real writer and tables (plan D7, D9). Page limit 5 and at most 3 pages per run (ruling R3).
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class DeltaPollerTest extends PostgresTestBase {

    private static final String BASE = "http://delta.test";
    private static final int PAGE_LIMIT = 5;
    private static final int MAX_PAGES = 3;
    private static final Duration OVERLAP = Duration.ofSeconds(120);
    private static final Instant T0 = Instant.parse("2034-11-04T10:00:00Z");
    private static final long USER = 4242L;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private DeltaWatermarkStore watermarks;
    @Autowired private DeadLetterStore deadLetters;
    @Autowired private TxTypeRegistry txTypes;
    @Autowired private PartitionManager partitionManager;
    @Autowired private MeterRegistry meters;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;

    private MockRestServiceServer server;
    private DeltaPoller poller;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 2);
        clearProjection();
        DeltaProperties properties = new DeltaProperties(
                BASE, null, PAGE_LIMIT, OVERLAP, MAX_PAGES, null, null, null);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeltaClient(builder.build(), properties, objectMapper);
        this.properties = properties;
        poller = pollerWith(writer);
    }

    private DeltaClient client;
    private DeltaProperties properties;

    private DeltaPoller pollerWith(ProjectionWriter projectionWriter) {
        return new DeltaPoller(client, new DeltaRowDecoder(properties), txTypes, projectionWriter, watermarks,
                deadLetters, properties, new DeltaPollMetrics(meters, watermarks, clock));
    }

    /**
     * The real writer, except that a one-row batch (the per-row fallback) whose row has sourceId
     * {@code failingId} throws {@code failure} the first {@code times} times.
     */
    private ProjectionWriter failingAlone(String failingId, RuntimeException failure, int times) {
        ProjectionWriter flaky = org.mockito.Mockito.mock(ProjectionWriter.class);
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(times);
        org.mockito.Mockito.when(flaky.applyDelta(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            wqs.projection.DeltaBatch batch = call.getArgument(0);
            if (batch.rows().size() == 1 && batch.rows().get(0).item().sourceId().equals(failingId)
                    && left.getAndDecrement() > 0) {
                throw failure;
            }
            return writer.applyDelta(batch);
        });
        return flaky;
    }

    private void expect(Instant since, String body) {
        server.expect(requestTo(BASE + "/wallet/delta?from=" + since))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** Verifies the previous run's requests and clears them, so the next run's can be declared. */
    private void nextRun() {
        server.verify();
        server.reset();
    }

    private static Instant v(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    private static String prizeAt(String id, Instant version) {
        return prize(id, USER, T0, version, "UNCLAIMED", "lotto", null);
    }

    @Test
    void first_poll_uses_epoch_minus_nothing() {
        expect(Instant.EPOCH, "[]");

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(1, 0, false));
        assertThat(watermarks.read()).as("an empty page leaves the watermark unset").isEmpty();
    }

    @Test
    void next_poll_starts_at_watermark_minus_overlap() {
        expect(Instant.EPOCH, page(prizeAt("1", v(1)), payment("11", 1, USER, T0, v(3), 3)));
        poller.runOnce();
        nextRun();
        assertThat(watermarks.read()).contains(v(3));

        expect(v(3).minus(OVERLAP), "[]");
        poller.runOnce();

        server.verify();
        assertThat(watermarks.read()).contains(v(3));
    }

    /** Review Focus 1: the overlap window re-reads rows already applied; nothing is written for them. */
    @Test
    void overlap_reread_writes_nothing() {
        String body = page(prizeAt("1", v(1)), payment("11", 1, USER, T0, v(2), 3), limit("21", USER, T0, v(2)));
        expect(Instant.EPOCH, body);
        poller.runOnce();
        nextRun();
        ProjectionSnapshot before = ProjectionSnapshot.of(dsl());
        double duplicates = meters.counter("wqs.delta.rows", "outcome", "duplicate").count();

        expect(v(2).minus(OVERLAP), body);
        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(1, 3, false));
        assertThat(ProjectionSnapshot.of(dsl())).isEqualTo(before);
        assertThat(meters.counter("wqs.delta.rows", "outcome", "duplicate").count()).isEqualTo(duplicates + 3);
        assertThat(count("wqDeadLetter")).isZero();
        assertThat(watermarks.read()).contains(v(2));
    }

    @Test
    void saturated_page_with_distinct_versions_polls_again_in_the_same_run() {
        double saturated = meters.counter(DeltaPollMetrics.PAGE_SATURATED).count();
        expect(Instant.EPOCH, page(rows(1, PAGE_LIMIT)));
        expect(v(PAGE_LIMIT).minus(OVERLAP), page(prizeAt("100", v(6)), prizeAt("101", v(7))));

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(2, PAGE_LIMIT + 2, false));
        assertThat(watermarks.read()).contains(v(7));
        assertThat(count("wqCcItem")).isEqualTo(PAGE_LIMIT + 2);
        assertThat(meters.counter(DeltaPollMetrics.PAGE_SATURATED).count()).isEqualTo(saturated + 1);
    }

    @Test
    void timestamp_cliff_alarms_and_does_not_advance() {
        expect(Instant.EPOCH, page(prizeAt("1", v(1))));
        poller.runOnce();
        nextRun();
        double cliffs = meters.counter(DeltaPollMetrics.TIMESTAMP_CLIFF).count();
        List<String> oneVersion = new ArrayList<>();
        for (int i = 0; i < PAGE_LIMIT; i++) {
            oneVersion.add(prizeAt(Integer.toString(200 + i), v(9)));
        }
        // Exactly one request: the run ends on the cliff instead of polling the same page again.
        expect(v(1).minus(OVERLAP), page(oneVersion));

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(1, PAGE_LIMIT, true));
        assertThat(meters.counter(DeltaPollMetrics.TIMESTAMP_CLIFF).count()).isEqualTo(cliffs + 1);
        assertThat(watermarks.read()).as("the cliff does not advance the watermark").contains(v(1));
        // The page's rows are still applied: versioned upserts are idempotent.
        assertThat(count("wqCcItem")).isEqualTo(1 + PAGE_LIMIT);
    }

    /**
     * Review C1: more than page-limit rows inside [W - overlap, W] would stall the cursor forever.
     * The run refetches once from W itself (no overlap) and applies the newer rows above W.
     */
    @Test
    void saturated_overlap_page_refetches_from_the_watermark_and_progresses() {
        expect(Instant.EPOCH, page(prizeAt("1", v(10))));
        poller.runOnce();
        nextRun();
        double overlapSaturated = meters.counter(DeltaPollMetrics.OVERLAP_SATURATED).count();
        expect(v(10).minus(OVERLAP), page(prizeAt("2", v(8)), prizeAt("3", v(9)), prizeAt("4", v(9)),
                prizeAt("5", v(10)), prizeAt("1", v(10))));
        expect(v(10), page(prizeAt("1", v(10)), prizeAt("6", v(11)), prizeAt("7", v(12))));

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(2, PAGE_LIMIT + 3, false));
        assertThat(watermarks.read()).contains(v(12));
        assertThat(count("wqCcItem")).isEqualTo(7);
        assertThat(meters.counter(DeltaPollMetrics.OVERLAP_SATURATED).count()).isEqualTo(overlapSaturated + 1);
    }

    /** The exact refetch from W is itself a full page all at W: the timestamp cliff. */
    @Test
    void saturated_refetch_all_at_the_watermark_is_a_cliff() {
        expect(Instant.EPOCH, page(prizeAt("1", v(10))));
        poller.runOnce();
        nextRun();
        expect(v(10).minus(OVERLAP), page(prizeAt("2", v(8)), prizeAt("3", v(9)), prizeAt("4", v(10)),
                prizeAt("5", v(10)), prizeAt("1", v(10))));
        expect(v(10), page(prizeAt("1", v(10)), prizeAt("4", v(10)), prizeAt("5", v(10)),
                prizeAt("6", v(10)), prizeAt("7", v(10))));

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(2, 2 * PAGE_LIMIT, true));
        assertThat(watermarks.read()).contains(v(10));
    }

    /** Review M2: a first-poll full page with no readable version is not fetched again. */
    @Test
    void saturated_first_page_without_versions_ends_the_run() {
        List<String> garbage = new ArrayList<>();
        for (int i = 0; i < PAGE_LIMIT; i++) {
            garbage.add("{\"source\":\"walMystery\",\"id\":" + i + "}");
        }
        expect(Instant.EPOCH, page(garbage)); // exactly one request

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(1, PAGE_LIMIT, false));
        assertThat(watermarks.read()).isEmpty();
    }

    @Test
    void max_pages_per_run_bounds_the_loop() {
        Instant since = Instant.EPOCH;
        for (int p = 0; p < MAX_PAGES; p++) {
            int first = p * PAGE_LIMIT + 1;
            expect(since, page(rows(first, PAGE_LIMIT)));
            since = v(first + PAGE_LIMIT - 1).minus(OVERLAP);
        }

        PollOutcome outcome = poller.runOnce();

        server.verify(); // exactly MAX_PAGES requests, though every page was saturated
        assertThat(outcome).isEqualTo(new PollOutcome(MAX_PAGES, MAX_PAGES * PAGE_LIMIT, false));
        assertThat(watermarks.read()).contains(v(MAX_PAGES * PAGE_LIMIT));
    }

    @Test
    void http_500_and_timeout_and_garbage_leave_watermark_untouched() {
        expect(Instant.EPOCH, page(prizeAt("1", v(1))));
        poller.runOnce();
        nextRun();
        ProjectionSnapshot before = ProjectionSnapshot.of(dsl());
        double failures = meters.counter(DeltaPollMetrics.POLL_FAILURES).count();
        String url = BASE + "/wallet/delta?from=" + v(1).minus(OVERLAP);
        server.expect(requestTo(url)).andRespond(withServerError());
        server.expect(requestTo(url)).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(url)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(poller::runOnce).isInstanceOf(DeltaFetchException.class);
        }

        server.verify();
        assertThat(watermarks.read()).contains(v(1));
        assertThat(ProjectionSnapshot.of(dsl())).isEqualTo(before);
        assertThat(meters.counter(DeltaPollMetrics.POLL_FAILURES).count()).isEqualTo(failures + 4);
    }

    @Test
    void poison_row_is_dead_lettered_and_the_rest_of_the_page_applies() {
        double deadLettered = meters.counter("wqs.delta.rows", "outcome", "dead-lettered").count();
        expect(Instant.EPOCH, page(
                prizeAt("1", v(1)),
                payment("42", 1, USER, T0, v(5), 3, 7),          // paymentInOut 7: poison, version readable
                "{\"source\":\"walMystery\",\"updated\":\"garbage\"}", // nothing readable
                limit("21", USER, T0, v(2))));

        PollOutcome outcome = poller.runOnce();

        server.verify();
        assertThat(outcome).isEqualTo(new PollOutcome(1, 4, false));
        assertThat(count("wqCcItem")).isEqualTo(2);
        assertThat(watermarks.read())
                .as("advances past the poison row's readable version, so it cannot pin the cursor")
                .contains(v(5));
        List<Record> letters = dsl().fetch(
                "select \"stream\", \"source\", \"sourceId\", \"payload\"::text as payload from \"wqDeadLetter\""
                        + " order by \"deadLetterId\"");
        assertThat(letters).hasSize(2);
        assertThat(letters.get(0).get("stream", String.class)).isEqualTo(DeltaWatermarkStore.WALLET_DELTA);
        assertThat(letters.get(0).get("source", Short.class)).isEqualTo((short) SourceKind.PAYMENT.code());
        assertThat(letters.get(0).get("sourceId", String.class)).isEqualTo("42");
        assertThat(letters.get(0).get("payload", String.class))
                .contains("\"paymentInOut\": 7")
                .doesNotContain("payCardNumber").doesNotContain("xmlData");
        assertThat(letters.get(1).get("source", Short.class)).isNull();
        assertThat(letters.get(1).get("sourceId", String.class)).isNull();
        assertThat(letters.get(1).get("payload", String.class)).contains("walMystery");
        assertThat(meters.counter("wqs.delta.rows", "outcome", "dead-lettered").count()).isEqualTo(deadLettered + 2);
    }

    /** Security review: a dead letter stores only allowlisted wire fields, never PII or blobs. */
    @Test
    void poison_payment_dead_letter_keeps_only_allowlisted_fields() {
        String poison = payment("43", 1, USER, T0, v(5), 3, 7).replace("}",
                ",\"payEmail\":\"ENC:mail\",\"payBankAccount\":\"ENC:bank\",\"PayCardNumber\":\"ENC:case\","
                        + "\"description\":{\"payCardNumber\":\"ENC:nested\"},\"accountNumber\":\"10001234\"}");
        expect(Instant.EPOCH, page(poison, "[\"ENC:in-an-array\"]", "\"ENC:a-scalar\""));

        poller.runOnce();

        server.verify();
        List<String> payloads = dsl().fetch("select \"payload\"::text from \"wqDeadLetter\" order by \"deadLetterId\"")
                .getValues(0, String.class);
        assertThat(payloads).hasSize(3);
        for (String payload : payloads) {
            assertThat(payload).doesNotContain("ENC:").doesNotContainIgnoringCase("payCardNumber")
                    .doesNotContain("payEmail").doesNotContain("payBankAccount").doesNotContain("xmlData")
                    .doesNotContain("accountNumber").doesNotContain("<payment");
        }
        assertThat(payloads.get(0)).contains("\"paymentId\": 43").contains("\"paymentInOut\": 7")
                .contains("\"status\": 3").doesNotContain("description");
        assertThat(payloads.get(1)).contains("\"type\": \"ARRAY\"").contains("sha256");
        assertThat(payloads.get(2)).contains("\"type\": \"STRING\"").contains("sha256");
    }

    /** Security review: an error response's body never reaches the exception or the log. */
    @Test
    void http_error_body_is_not_in_the_exception_or_the_log(CapturedOutput output) {
        server.expect(requestTo(BASE + "/wallet/delta?from=" + Instant.EPOCH))
                .andRespond(withServerError().body("SECRET-BODY").contentType(MediaType.TEXT_PLAIN));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(poller::runOnce);

        assertThat(thrown).isInstanceOf(DeltaFetchException.class).hasMessageContaining("500");
        java.io.StringWriter trace = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain("SECRET-BODY");
        assertThat(output.getAll()).contains("Delta poll failed").doesNotContain("SECRET-BODY");
    }

    /** A row the writer cannot apply (here: a month before the provisioning floor) fails alone. */
    @Test
    void row_failing_in_the_writer_is_dead_lettered_alone() {
        expect(Instant.EPOCH, page(
                prizeAt("1", v(1)),
                prize("2", USER, Instant.parse("2001-01-01T00:00:00Z"), v(4), "UNCLAIMED", "lotto", null),
                limit("21", USER, T0, v(2))));

        poller.runOnce();

        server.verify();
        assertThat(count("wqCcItem")).isEqualTo(2);
        assertThat(watermarks.read()).contains(v(4));
        Record letter = dsl().fetchOne("select \"source\", \"sourceId\" from \"wqDeadLetter\"");
        assertThat(letter.get("source", Short.class)).isEqualTo((short) SourceKind.CLAIMABLE_PRIZE.code());
        assertThat(letter.get("sourceId", String.class)).isEqualTo("2");
    }

    /**
     * Review I1: a transient failure in the per-row fallback (no connection; a 40P01 deadlock)
     * fails the run -- nothing dead-lettered, watermark unchanged -- and the next run applies it.
     */
    @Test
    void transient_failure_in_the_row_fallback_fails_the_run_and_the_next_run_applies() {
        List<RuntimeException> transients = List.of(
                new org.springframework.jdbc.CannotGetJdbcConnectionException("pool exhausted"),
                new org.springframework.dao.PessimisticLockingFailureException("ERROR: deadlock detected (40P01)"));
        for (RuntimeException failure : transients) {
            clearProjection();
            server.reset();
            DeltaPoller flaky = pollerWith(failingAlone("1", failure, 1));
            String body = page(
                    prizeAt("1", v(1)),
                    prize("2", USER, Instant.parse("2001-01-01T00:00:00Z"), v(4), "UNCLAIMED", "lotto", null),
                    limit("21", USER, T0, v(2)));
            expect(Instant.EPOCH, body);
            expect(Instant.EPOCH, body);
            double failures = meters.counter(DeltaPollMetrics.POLL_FAILURES).count();

            assertThatThrownBy(flaky::runOnce).isSameAs(failure);
            assertThat(count("wqDeadLetter")).as("%s is not poison", failure).isZero();
            assertThat(watermarks.read()).isEmpty();
            assertThat(meters.counter(DeltaPollMetrics.POLL_FAILURES).count()).isEqualTo(failures + 1);

            flaky.runOnce();

            server.verify();
            assertThat(count("wqCcItem")).isEqualTo(2);
            assertThat(count("wqDeadLetter")).as("only the out-of-window row").isEqualTo(1);
            assertThat(watermarks.read()).contains(v(4));
        }
    }

    /** A data-integrity rejection in the per-row fallback is still poison: dead-lettered alone. */
    @Test
    void data_integrity_violation_in_the_row_fallback_is_dead_lettered() {
        DeltaPoller flaky = pollerWith(failingAlone("1", new org.springframework.dao.DataIntegrityViolationException("bad"), 9));
        expect(Instant.EPOCH, page(prizeAt("1", v(1)),
                prize("2", USER, Instant.parse("2001-01-01T00:00:00Z"), v(4), "UNCLAIMED", "lotto", null),
                limit("21", USER, T0, v(2))));

        flaky.runOnce();

        server.verify();
        assertThat(count("wqCcItem")).isEqualTo(1);
        assertThat(dsl().fetch("select \"sourceId\" from \"wqDeadLetter\" order by \"sourceId\"")
                .getValues(0, String.class)).containsExactly("1", "2");
        assertThat(watermarks.read()).contains(v(4));
    }

    /** {@code n} prizes with ids and versions (minutes after T0) {@code first .. first + n - 1}. */
    private static List<String> rows(int first, int n) {
        List<String> rows = new ArrayList<>();
        for (int i = first; i < first + n; i++) {
            rows.add(prizeAt(Integer.toString(i), v(i)));
        }
        return rows;
    }

    private static long count(String table) {
        return dsl().fetchOne("select count(*) from \"" + table + "\"").get(0, Long.class);
    }
}
