package wqs.repository;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.config.PartitionProperties;
import wqs.exception.RejectedException;

/**
 * Partition DDL: creates monthly partitions of a partitioned table on demand ({@code "wqPrjTransaction_2026_10"}
 * holds October 2026, UTC).
 *
 * <p>Each partition is created in its own short transaction (REQUIRES_NEW): creating one locks
 * the parent table, and that lock must not be held for the rest of an ingest transaction.
 * A concurrent creator that wins the race is fine: the partition exists either way.
 */
@Repository
public class PartitionRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private final PartitionProperties properties;
    private final Clock clock;
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    public PartitionRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                      PartitionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Ensures {@code table} has the partition holding {@code at}. A month outside the allowed range
     * is a garbage timestamp: {@link RejectedException} {@code occurred-at-out-of-range}.
     */
    public void ensure(String table, Instant at) {
        YearMonth month = YearMonth.from(at.atOffset(ZoneOffset.UTC));
        YearMonth latest = YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusMonths(properties.maxMonthsAhead());
        if (month.isBefore(properties.floor()) || month.isAfter(latest)) {
            throw new RejectedException("occurred-at-out-of-range",
                    "month outside the partition range " + properties.floor() + ".." + latest);
        }
        String partition = "%s_%04d_%02d".formatted(table, month.getYear(), month.getMonthValue());
        if (known.contains(partition)) {
            return;
        }
        // DDL cannot take bind parameters. Every piece here is generated, never input: the table
        // name is a constant from the caller and the bounds are formatted from a YearMonth.
        String ddl = "create table if not exists \"%s\" partition of \"%s\" for values from ('%s') to ('%s')"
                .formatted(partition, table, month.atDay(1) + "T00:00:00Z", month.plusMonths(1).atDay(1) + "T00:00:00Z");
        try {
            ownTransaction.executeWithoutResult(status -> jdbc.sql(ddl).update());
        } catch (DataAccessException raced) {
            // Lost a race with another creator, or a real failure: the existence check tells which.
            if (!exists(partition)) {
                throw raced;
            }
        }
        known.add(partition);
    }

    private boolean exists(String partition) {
        return jdbc.sql("select to_regclass(:name) is not null")
                .param("name", "\"" + partition + "\"")
                .query(Boolean.class).single();
    }
}
