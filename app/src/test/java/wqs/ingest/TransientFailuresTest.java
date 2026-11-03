package wqs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

/** The one transient-failure allowlist shared by the stream consumer and the delta poller. */
class TransientFailuresTest {

    @Test
    void spring_transient_and_infrastructure_exceptions_are_transient() {
        assertThat(TransientFailures.isTransient(new TransientDataAccessResourceException("blip"))).isTrue();
        assertThat(TransientFailures.isTransient(new RecoverableDataAccessException("recoverable"))).isTrue();
        assertThat(TransientFailures.isTransient(new DataAccessResourceFailureException("down"))).isTrue();
        assertThat(TransientFailures.isTransient(new CannotGetJdbcConnectionException("pool timeout"))).isTrue();
        assertThat(TransientFailures.isTransient(new PessimisticLockingFailureException("lock"))).isTrue();
        assertThat(TransientFailures.isTransient(new CannotAcquireLockException("lock"))).isTrue();
        assertThat(TransientFailures.isTransient(new QueryTimeoutException("slow"))).isTrue();
    }

    @Test
    void sql_states_of_deadlock_serialization_lock_and_connection_are_transient() {
        for (String state : new String[] {"40P01", "40001", "55P03", "08000", "08001", "08003", "08006", "08P01"}) {
            assertThat(TransientFailures.isTransient(new SQLException("x", state))).as(state).isTrue();
        }
    }

    @Test
    void a_transient_cause_anywhere_in_the_chain_is_transient() {
        SQLException deadlock = new SQLException("deadlock detected", "40P01");
        assertThat(TransientFailures.isTransient(
                new UncategorizedSQLException("apply", "update ...", deadlock))).isTrue();
        assertThat(TransientFailures.isTransient(new IllegalStateException("wrapped",
                new RuntimeException("again", new CannotGetJdbcConnectionException("pool"))))).isTrue();
    }

    @Test
    void bugs_and_data_rejections_are_not_transient() {
        assertThat(TransientFailures.isTransient(new NullPointerException())).isFalse();
        assertThat(TransientFailures.isTransient(new IllegalStateException("bug"))).isFalse();
        assertThat(TransientFailures.isTransient(new IllegalArgumentException("poison"))).isFalse();
        assertThat(TransientFailures.isTransient(new DataIntegrityViolationException("23505"))).isFalse();
        assertThat(TransientFailures.isTransient(new SQLException("unique", "23505"))).isFalse();
        assertThat(TransientFailures.isTransient(new SQLException("no state"))).isFalse();
        assertThat(TransientFailures.isTransient(null)).isFalse();
    }

    @Test
    void a_cyclic_cause_chain_terminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(TransientFailures.isTransient(b)).isFalse();
    }
}
