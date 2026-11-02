package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Ruling F2: the API clamps every range into DataDomain [1970-01, 2200-01), which is exact only
 * if no partition can be provisioned outside it, so such a provisioning window fails startup.
 * "Now" is fixed at 2026-11-02, so the last provisionable month is 2026-11 + max-months-ahead.
 */
class PartitionWindowDomainTest {

    /** 2026-11 + 2077 months = 2199-12, the last month inside the domain. */
    private static final int LAST_INSIDE = 2077;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC))
            .withBean(PartitionManager.class, () -> new PartitionManager(DSL.using(SQLDialect.POSTGRES)))
            .withBean(PlatformTransactionManager.class, NoTransactions::new)
            .withBean(PartitionProvisioner.class);

    @Test
    void the_defaults_start() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void a_provision_floor_before_1970_fails_startup() {
        runner.withPropertyValues("wqs.partition.provision-floor=1969-12")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .rootCause().hasMessageContaining("provision-floor"));
        runner.withPropertyValues("wqs.partition.provision-floor=1970-01")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void a_window_reaching_2200_fails_startup() {
        runner.withPropertyValues("wqs.partition.max-months-ahead=" + (LAST_INSIDE + 1))
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .rootCause().hasMessageContaining("max-months-ahead"));
        runner.withPropertyValues("wqs.partition.max-months-ahead=" + LAST_INSIDE)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** The provisioner only builds a TransactionTemplate at construction; nothing runs here. */
    private static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }
}
