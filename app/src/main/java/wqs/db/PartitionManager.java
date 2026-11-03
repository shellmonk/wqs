package wqs.db;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Creates monthly partitions ahead of time. Application-managed rather than pg_partman,
 * because the stock Testcontainers Postgres image lacks the extension and partition
 * rollover must be testable locally (spec section 5.8).
 *
 * JUSTIFIED EXCEPTION to spec section 2.2 rule 1 (no string-built SQL): Postgres accepts no bind
 * parameters in DDL, so CREATE TABLE ... PARTITION OF ... FOR VALUES is built as text. Every input
 * to it is program-generated -- an enum constant and a LocalDate -- and all identifiers pass
 * through quoteIdent. The pg_inherits existence check binds its (quoted) names.
 */
@Component
public class PartitionManager {

    // uuuu (proleptic year), not yyyy (year-of-era): year-of-era is ambiguous for years <= 0.
    // Identical output for every positive year, so existing partition names are unchanged.
    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("uuuu_MM");

    // Defensive ceiling on runwayMonths' DB-round-trip loop. 5 years of consecutive monthly
    // partitions is far beyond any real operating runway; if state ever makes the loop run
    // that long, something is wrong and we should stop scanning rather than hammer the
    // database on every scheduled gauge scrape (Task 8).
    private static final int MAX_RUNWAY_MONTHS = 60;

    private static final String PARTITION_EXISTS = """
            select exists(
                select 1 from pg_inherits
                where inhrelid = to_regclass(?)::oid
                  and inhparent = to_regclass(?)::oid)
            """;

    private final JdbcClient jdbc;

    public PartitionManager(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    /**
     * Number of consecutive months, starting at {@code from}, for which all partitions exist.
     * Capped at {@link #MAX_RUNWAY_MONTHS} so unexpected database state cannot turn a routine
     * scrape into an unbounded sequence of round trips.
     */
    public int runwayMonths(YearMonth from) {
        int months = 0;
        while (months < MAX_RUNWAY_MONTHS && allPartitionsExist(from.plusMonths(months))) {
            months++;
        }
        return months;
    }

    private boolean createPartitionIfAbsent(PartitionedTable table, YearMonth month) {
        String child = partitionName(table, month);
        if (partitionExists(table, child)) {
            return false;
        }

        // Bounds are stated as explicit UTC instants ('...Z'), not bare calendar-date literals.
        // A bare date literal like '2031-01-01' is resolved against the session TimeZone GUC
        // when cast to timestamptz, so if that GUC ever drifted from UTC the partition bounds
        // would silently shift off UTC calendar months -- not creating a gap, but causing a
        // UTC-month query to span two partitions and quietly lose pruning (spec 7.1: UTC
        // throughout). An explicit 'Z' offset is parsed the same way regardless of the GUC,
        // and casts unchanged to a date column too, so this stays type-agnostic.
        String lower = utcInstantLiteral(month.atDay(1));
        String upper = utcInstantLiteral(month.plusMonths(1).atDay(1));

        jdbc.sql("create table if not exists " + quoteIdent(child)
                        + " partition of " + quoteIdent(table.tableName())
                        + " for values from ('" + lower + "') to ('" + upper + "')")
                .update();
        return true;
    }

    private boolean allPartitionsExist(YearMonth month) {
        for (PartitionedTable table : PartitionedTable.values()) {
            if (!partitionExists(table, partitionName(table, month))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code child} exists AND is attached as a partition of {@code table} specifically
     * -- not merely a same-named relation anywhere reachable on the search path. A bare
     * pg_class.relname lookup would answer "does a relation with this name exist", which is the
     * wrong question: a false positive here makes ensurePartitionsAhead skip creating the real
     * partition, which is the exact silent-gap failure this class exists to prevent. Joining
     * pg_inherits against the parent's own resolved oid (to_regclass, so both names resolve via
     * the same search-path rules the DDL itself uses) answers the real question: is there a
     * partition of this named parent with this exact child name.
     */
    private boolean partitionExists(PartitionedTable table, String child) {
        return Boolean.TRUE.equals(jdbc.sql(PARTITION_EXISTS)
                .params(quoteIdent(child), quoteIdent(table.tableName()))
                .query(Boolean.class)
                .single());
    }

    private static String partitionName(PartitionedTable table, YearMonth month) {
        return table.tableName() + "_" + month.format(MONTH_SUFFIX);
    }

    private static String utcInstantLiteral(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    private static String quoteIdent(String raw) {
        return '"' + raw.replace("\"", "\"\"") + '"';
    }
}
