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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The empty service starts against real Postgres and RabbitMQ, and each piece of plumbing works. */
class BootstrapTest extends IntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcClient jdbcClient;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Scheduler scheduler;
    @Autowired Environment streamEnvironment;

    @Test
    void healthIsUp() {
        String body = restTemplate.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayAppliedTheBaseline() {
        List<String> applied = jdbcClient.sql("select version from flyway_schema_history where success")
                .query(String.class).list();
        assertThat(applied).containsExactly("1");
    }

    @Test
    void quartzRunsClusteredOnItsJdbcStore() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.getMetaData().isJobStoreClustered()).isTrue();
        assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
    }

    @Test
    void writerPoolCanWrite() {
        jdbcClient.sql("create table if not exists bootstrapProbe (id int)").update();
        jdbcClient.sql("insert into bootstrapProbe values (:id)").param("id", 1).update();
        jdbcClient.sql("drop table bootstrapProbe").update();
    }

    @Test
    void aReadWriteTransactionRunsOnTheWriterPool() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String name = tx.execute(status -> applicationName());
        assertThat(name).isEqualTo("wqs-writer");
    }

    @Test
    void aReadOnlyTransactionRunsOnTheReaderPoolInRepeatableRead() {
        TransactionTemplate tx = readOnlyRepeatableRead();
        List<String> settings = tx.execute(status -> List.of(
                applicationName(),
                jdbcClient.sql("show transaction_isolation").query(String.class).single(),
                jdbcClient.sql("show transaction_read_only").query(String.class).single()));
        assertThat(settings).containsExactly("wqs-reader", "repeatable read", "on");
    }

    @Test
    void theReaderPoolRefusesWrites() {
        TransactionTemplate tx = readOnlyRepeatableRead();
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                jdbcClient.sql("create table readerProbe (id int)").update()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only");
    }

    private TransactionTemplate readOnlyRepeatableRead() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }

    private String applicationName() {
        return jdbcClient.sql("select current_setting('application_name')").query(String.class).single();
    }

    @Test
    void aPublishedStreamMessageReachesAConsumer() throws InterruptedException {
        String stream = "bootstrap-probe";
        streamEnvironment.streamCreator().stream(stream).create();
        // send() is asynchronous: wait for the broker's confirm before closing the producer.
        CountDownLatch confirmed = new CountDownLatch(1);
        try (Producer producer = streamEnvironment.producerBuilder().stream(stream).build()) {
            producer.send(producer.messageBuilder().addData("hello".getBytes(StandardCharsets.UTF_8)).build(),
                    status -> { if (status.isConfirmed()) confirmed.countDown(); });
            assertThat(confirmed.await(10, TimeUnit.SECONDS)).as("broker confirmed the message").isTrue();
        }
        List<String> received = new CopyOnWriteArrayList<>();
        try (var consumer = streamEnvironment.consumerBuilder().stream(stream)
                .offset(OffsetSpecification.first())
                .messageHandler((context, message) ->
                        received.add(new String(message.getBodyAsBinary(), StandardCharsets.UTF_8)))
                .build()) {
            await().atMost(Duration.ofSeconds(10)).until(() -> received.contains("hello"));
        } finally {
            streamEnvironment.deleteStream(stream);
        }
    }
}
