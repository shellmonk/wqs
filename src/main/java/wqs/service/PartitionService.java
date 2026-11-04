package wqs.service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import wqs.config.PartitionProperties;
import wqs.exception.RejectedException;

/**
 * Creates monthly partitions on demand ({@code "wqPrjTransaction_2026_10"} holds October 2026, UTC).
 * A concurrent creator that wins the race is fine: the partition exists either way.
 */
@Service
public class PartitionService {

    private final PartitionDdlService ddl;
    private final PartitionProperties properties;
    private final Clock clock;
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    public PartitionService(PartitionDdlService ddl, PartitionProperties properties, Clock clock) {
        this.ddl = ddl;
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
        String sql = "create table if not exists \"%s\" partition of \"%s\" for values from ('%s') to ('%s')"
                .formatted(partition, table, month.atDay(1) + "T00:00:00Z", month.plusMonths(1).atDay(1) + "T00:00:00Z");
        try {
            ddl.execute(sql);
        } catch (DataAccessException raced) {
            // Lost a race with another creator, or a real failure: the existence check tells which.
            if (!ddl.exists(partition)) {
                throw raced;
            }
        }
        known.add(partition);
    }
}
