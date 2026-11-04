package wqs.stream;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import wqs.config.StreamProperties;

/**
 * Makes sure only one replica consumes the stream: a Postgres session-level advisory lock named
 * after the stream, held on one dedicated writer connection for as long as this replica consumes.
 * If the process or its connection dies, Postgres releases the lock and a standby replica takes over.
 *
 * <p>Not thread-safe: used only by the consumer's supervisor thread.
 */
@Component
@ConditionalOnProperty(name = "wqs.stream.enabled", havingValue = "true")
public class StreamLeadership {

    private static final Logger log = LoggerFactory.getLogger(StreamLeadership.class);

    private final DataSource writer;
    private final String lockName;
    private Connection connection;

    public StreamLeadership(@Qualifier("writerDataSource") DataSource writer, StreamProperties properties) {
        this.writer = writer;
        this.lockName = "wqs-stream:" + properties.name();
    }

    /** Takes the lock if no other replica holds it. True when this replica holds it (now or already). */
    public boolean tryAcquire() {
        if (connection != null) {
            return stillHeld();
        }
        try {
            Connection c = writer.getConnection();
            c.setAutoCommit(true);
            if (bool(c, "select pg_try_advisory_lock(hashtextextended(?, 0))")) {
                connection = c;
                return true;
            }
            c.close();
        } catch (SQLException e) {
            log.warn("Cannot try the stream lock {} ({})", lockName, e.getClass().getSimpleName());
        }
        return false;
    }

    /** True while the lock's connection is alive and still holds the lock. */
    public boolean stillHeld() {
        if (connection == null) {
            return false;
        }
        try {
            return bool(connection, """
                    select exists (select 1 from pg_locks
                                   where locktype = 'advisory' and pid = pg_backend_pid() and granted
                                     and objid = (hashtextextended(?, 0) & 4294967295)::oid)
                    """);
        } catch (SQLException e) {
            return false;
        }
    }

    public void release() {
        Connection c = connection;
        connection = null;
        if (c == null) {
            return;
        }
        try (c) {
            bool(c, "select pg_advisory_unlock(hashtextextended(?, 0))");
        } catch (SQLException e) {
            // the connection is gone, and with it the lock
        }
    }

    private boolean bool(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
