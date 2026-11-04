package wqs.util;

import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Which failures are retried instead of dead-lettered: an explicit ALLOWLIST of transient
 * infrastructure failures, looked for anywhere in the cause chain. Everything else (bad data, a
 * constraint violation, a bug) is dead-lettered, so an unknown failure can never stall ingest
 * in a retry loop, and a database blip can never dead-letter a valid message.
 *
 * <p>Transient: Spring's {@link TransientDataAccessException} (lock, deadlock, query timeout),
 * {@link RecoverableDataAccessException}, {@link DataAccessResourceFailureException} (no
 * connection), {@link PessimisticLockingFailureException}, {@link QueryTimeoutException}, and an
 * {@link SQLException} with SQLState 40P01 (deadlock), 40001 (serialization failure), 55P03 (lock
 * not available) or class 08 (connection).
 */
public final class TransientFailures {

    /** Guards against a cyclic cause chain. */
    private static final int MAX_DEPTH = 32;

    private TransientFailures() {}

    public static boolean isTransient(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof PessimisticLockingFailureException
                    || t instanceof QueryTimeoutException) {
                return true;
            }
            if (t instanceof SQLException sql && isTransientState(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientState(String state) {
        return state != null && (state.equals("40P01") || state.equals("40001") || state.equals("55P03")
                || state.startsWith("08"));
    }
}
