package wqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.Producer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.config.ReaderJdbc;
import wqs.config.ReaderTx;

/** The empty service starts against real Postgres and RabbitMQ, and each piece of plumbing works. */
class BootstrapTest extends IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient writer;
    @Autowired @ReaderJdbc JdbcClient reader;
    @Autowired @ReaderTx TransactionTemplate readerTx;
    @Autowired Scheduler scheduler;
    @Autowired Environment streams;

    @Test
    void health_is_up() {
        String body = http.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void flyway_applied_the_baseline() {
        List<String> applied = writer.sql("select version from flyway_schema_history where success")
                .query(String.class).list();
        assertThat(applied).containsExactly("1");
    }

    @Test
    void quartz_runs_clustered_on_its_jdbc_store() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.getMetaData().isJobStoreClustered()).isTrue();
        assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
    }

    @Test
    void writer_pool_can_write() {
        writer.sql("create table if not exists \"bootstrapProbe\" (\"id\" int)").update();
        writer.sql("insert into \"bootstrapProbe\" values (:id)").param("id", 1).update();
        writer.sql("drop table \"bootstrapProbe\"").update();
    }

    @Test
    void reader_pool_refuses_writes_even_outside_a_transaction() {
        assertThatThrownBy(() -> reader.sql("create table \"readerProbe\" (\"id\" int)").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void reader_transaction_is_read_only_repeatable_read() {
        List<String> settings = readerTx.execute(status -> List.of(
                reader.sql("show transaction_isolation").query(String.class).single(),
                reader.sql("show transaction_read_only").query(String.class).single()));
        assertThat(settings).containsExactly("repeatable read", "on");
    }

    @Test
    void a_published_stream_message_reaches_a_consumer() throws InterruptedException {
        String stream = "bootstrap-probe";
        streams.streamCreator().stream(stream).create();
        // send() is asynchronous: wait for the broker's confirm before closing the producer.
        CountDownLatch confirmed = new CountDownLatch(1);
        try (Producer producer = streams.producerBuilder().stream(stream).build()) {
            producer.send(producer.messageBuilder().addData("hello".getBytes(StandardCharsets.UTF_8)).build(),
                    status -> { if (status.isConfirmed()) confirmed.countDown(); });
            assertThat(confirmed.await(10, TimeUnit.SECONDS)).as("broker confirmed the message").isTrue();
        }
        List<String> received = new CopyOnWriteArrayList<>();
        try (var consumer = streams.consumerBuilder().stream(stream)
                .offset(OffsetSpecification.first())
                .messageHandler((context, message) ->
                        received.add(new String(message.getBodyAsBinary(), StandardCharsets.UTF_8)))
                .build()) {
            await().atMost(Duration.ofSeconds(10)).until(() -> received.contains("hello"));
        } finally {
            streams.deleteStream(stream);
        }
    }
}
