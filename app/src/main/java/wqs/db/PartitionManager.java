package wqs.db;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Creates monthly partitions ahead of time. Application-managed rather than pg_partman,
 * because the stock Testcontainers Postgres image lacks the extension and partition
 * rollover must be testable locally (spec section 5.8).
 *
 * JUSTIFIED EXCEPTION to spec section 2.2 rule 1 (no string-built SQL): jOOQ has no DSL for
 * CREATE TABLE ... PARTITION OF, and Postgres does not accept bind parameters in FOR VALUES.
 * Every input here is program-generated -- an enum constant and a LocalDate -- and all
 * identifiers pass through quoteIdent.
 */
@Component
public class PartitionManager {

    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final DSLContext dsl;

    public PartitionManager(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Ensures partitions exist for [from, from + monthsAhead]. Returns how many were created. */
    public int ensurePartitionsAhead(YearMonth from, int monthsAhead) {
        int created = 0;
        for (int offset = 0; offset <= monthsAhead; offset++) {
            YearMonth month = from.plusMonths(offset);
            for (PartitionedTable table : PartitionedTable.values()) {
                if (createPartitionIfAbsent(table, month)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** Number of consecutive months, starting at {@code from}, for which all partitions exist. */
    public int runwayMonths(YearMonth from) {
        int months = 0;
        while (allPartitionsExist(from.plusMonths(months))) {
            months++;
        }
        return months;
    }

    private boolean createPartitionIfAbsent(PartitionedTable table, YearMonth month) {
        String child = partitionName(table, month);
        if (partitionExists(child)) {
            return false;
        }

        LocalDate lower = month.atDay(1);
        LocalDate upper = month.plusMonths(1).atDay(1);

        dsl.execute(
                "create table if not exists " + quoteIdent(child)
                        + " partition of " + quoteIdent(table.tableName())
                        + " for values from ('" + lower + "') to ('" + upper + "')");
        return true;
    }

    private boolean allPartitionsExist(YearMonth month) {
        for (PartitionedTable table : PartitionedTable.values()) {
            if (!partitionExists(partitionName(table, month))) {
                return false;
            }
        }
        return true;
    }

    private boolean partitionExists(String child) {
        return dsl.fetchExists(
                dsl.selectOne().from("pg_class").where("relname = ?", child));
    }

    private static String partitionName(PartitionedTable table, YearMonth month) {
        return table.tableName() + "_" + month.format(MONTH_SUFFIX);
    }

    private static String quoteIdent(String raw) {
        return '"' + raw.replace("\"", "\"\"") + '"';
    }
}
