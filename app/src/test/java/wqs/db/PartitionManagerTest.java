package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartitionManagerTest extends PostgresTestBase {

    private PartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = new PartitionManager(dsl());
        dropTestPartitions();
    }

    @Test
    void creates_partitions_for_the_requested_window() {
        int created = manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 2);

        // 3 months (Jan, Feb, Mar) x 7 partitioned tables
        assertThat(created).isEqualTo(21);
        assertThat(testPartitionNames())
                .contains("wqWebItem_2031_01", "wqWebItem_2031_02", "wqWebItem_2031_03",
                          "wqCcItem_2031_01", "wqCcItem_2031_02", "wqCcItem_2031_03",
                          "wqSession_2031_01", "wqSession_2031_02", "wqSession_2031_03",
                          "wqPlay_2031_01", "wqPlay_2031_02", "wqPlay_2031_03",
                          "wqWebHighLevel_2031_01", "wqWebHighLevel_2031_02", "wqWebHighLevel_2031_03",
                          "wqCcHighLevel_2031_01", "wqCcHighLevel_2031_02", "wqCcHighLevel_2031_03",
                          "wqCountDay_2031_01", "wqCountDay_2031_02", "wqCountDay_2031_03");
    }

    @Test
    void is_idempotent() {
        manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 2);

        int createdOnSecondRun = manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 2);

        assertThat(createdOnSecondRun).isZero();
    }

    @Test
    void accepted_rows_land_in_the_right_partition() {
        manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 0);

        dsl().execute("""
                      insert into "wqWebItem"
                        ("userId","itemId","source","sourceId","occurredAt","type","amount","currency")
                      values (1, 1, 1, 'tx-1', timestamptz '2031-01-15T10:00:00Z', 1, 10.0000, 'EUR')
                      """);

        Integer count = dsl()
                .fetchOne("select count(*)::int from \"wqWebItem_2031_01\"")
                .get(0, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void runway_counts_consecutive_future_partitions() {
        manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 3);

        assertThat(manager.runwayMonths(YearMonth.of(2031, 1))).isEqualTo(4);
        assertThat(manager.runwayMonths(YearMonth.of(2031, 5))).isZero();
    }

    @Test
    void a_row_at_the_exact_month_boundary_lands_in_the_new_month_not_the_old_one() {
        manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 1);

        dsl().execute("""
                      insert into "wqWebItem"
                        ("userId","itemId","source","sourceId","occurredAt","type","amount","currency")
                      values (1, 2, 1, 'tx-boundary', timestamptz '2031-02-01T00:00:00Z', 1, 10.0000, 'EUR')
                      """);

        Integer februaryCount = dsl()
                .fetchOne("select count(*)::int from \"wqWebItem_2031_02\"")
                .get(0, Integer.class);
        Integer januaryCount = dsl()
                .fetchOne("select count(*)::int from \"wqWebItem_2031_01\"")
                .get(0, Integer.class);
        assertThat(februaryCount).isEqualTo(1);
        assertThat(januaryCount).isZero();
    }

    @Test
    void date_partitioned_count_table_routes_first_and_last_day_of_a_month_correctly() {
        manager.ensurePartitionsAhead(YearMonth.of(2031, 1), 1);

        for (String day : List.of("2031-01-01", "2031-01-31", "2031-02-01", "2031-02-28")) {
            dsl().execute("""
                          insert into "wqCountDay"
                            ("userId","viewKind","pageUnit","productId","period","cntAll","cntExclCancelled")
                          values (1, 1, 0, 0, date '%s', 1, 1)
                          """.formatted(day));
        }

        assertThat(rowsIn("wqCountDay_2031_01")).containsExactlyInAnyOrder("2031-01-01", "2031-01-31");
        assertThat(rowsIn("wqCountDay_2031_02")).containsExactlyInAnyOrder("2031-02-01", "2031-02-28");
    }

    private List<String> rowsIn(String partition) {
        return dsl().fetch("select \"period\"::text from \"" + partition + "\"").getValues(0, String.class);
    }

    private void dropTestPartitions() {
        testPartitionNames().forEach(name -> dsl().execute("drop table if exists \"" + name + "\""));
    }

    /**
     * Test-month (2031) children that are actually attached as partitions of the managed
     * parent tables -- mirrors PartitionManager.partitionExists (R27): joins pg_inherits
     * against each parent's own resolved oid via to_regclass, rather than scanning pg_class
     * for any same-named relation. A bare pg_class/pg_inherits scan with no parentage check
     * previously also matched each partition's auto-created "_pkey" index (its pg_inherits row
     * points at the parent's *partitioned index*, a different oid than the parent *table*), so
     * an unordered scan occasionally handed an index name to "drop table", which Postgres
     * refuses even with IF EXISTS. Filtering by inhparent = the table's own oid excludes those
     * index rows by construction, the same way partitionExists excludes false-positive matches.
     */
    private List<String> testPartitionNames() {
        return Arrays.stream(PartitionedTable.values())
                .flatMap(table -> dsl()
                        .fetch("""
                               select c.relname
                               from pg_inherits i
                               join pg_class c on c.oid = i.inhrelid
                               where i.inhparent = to_regclass(?)::oid
                                 and c.relname like '%_2031_%'
                               """, "\"" + table.tableName() + "\"")
                        .getValues(0, String.class)
                        .stream())
                .toList();
    }
}
