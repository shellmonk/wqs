package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
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

        // 3 months (Jan, Feb, Mar) x 2 partitioned tables
        assertThat(created).isEqualTo(6);
        assertThat(existingPartitionNames())
                .contains("wqWebItem_2031_01", "wqWebItem_2031_02", "wqWebItem_2031_03",
                          "wqCcItem_2031_01", "wqCcItem_2031_02", "wqCcItem_2031_03");
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

    private void dropTestPartitions() {
        existingPartitionNames().stream()
                .filter(name -> name.contains("_2031_"))
                .forEach(name -> dsl().execute("drop table if exists \"" + name + "\""));
    }

    private List<String> existingPartitionNames() {
        return dsl()
                .fetch("""
                       select c.relname
                       from pg_class c
                       join pg_inherits i on i.inhrelid = c.oid
                       """)
                .getValues(0, String.class);
    }
}
