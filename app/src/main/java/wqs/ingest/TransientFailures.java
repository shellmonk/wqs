package wqs.ingest;

import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * The one rule, shared by the stream consumer (mapping and per-item apply) and the delta poller
 * (decode and per-row apply), for which failures are retried rather than dead-lettered: an explicit
 * ALLOWLIST of transient infrastructure failures, looked for anywhere in the cause chain. A failure
 * on it is retried -- the stream enters recovery, the poller's run fails -- with no dead letter and
 * no offset or watermark advance. Everything else (bad data, a data-integrity violation, a bug
 * such as an NPE) is dead-lettered, so an unknown failure can never stall ingestion in a retry loop.
 *
 * <p>Transient: Spring's {@link TransientDataAccessException} (which covers lock, deadlock and
 * query-timeout failures), {@link RecoverableDataAccessException}, {@link DataAccessResourceFailureException}
 * (which covers {@code CannotGetJdbcConnectionException}), {@link PessimisticLockingFailureException},
 * {@link QueryTimeoutException}, and an {@link SQLException} with SQLState 40P01 (deadlock), 40001
 * (serialization failure), 55P03 (lock not available) or class 08 (connection).
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
