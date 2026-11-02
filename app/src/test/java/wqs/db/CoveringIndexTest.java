package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Spec 7.7: the level-0 read path scans covering indexes; they must exist on every partition. */
class CoveringIndexTest extends PostgresTestBase {

    private static final List<String> PARENTS =
            List.of("wqWebItem", "wqCcItem", "wqWebHighLevel", "wqCcHighLevel");

    @BeforeAll
    static void createPartition() {
        // Created AFTER migration, so this proves the index propagates to future partitions.
        // Empty partitions are left behind deliberately (no rows; later tests re-ensure them).
        new PartitionManager(dsl()).ensurePartitionsAhead(YearMonth.of(2036, 7), 0);
    }

    @Test
    void every_item_and_high_level_partition_has_the_cover_index() {
        for (String parent : PARENTS) {
            List<String> childDefs = dsl().fetch("""
                    select pg_get_indexdef(ci.oid)
                    from pg_inherits ti
                    join pg_index ix on ix.indrelid = ti.inhrelid
                    join pg_class ci on ci.oid = ix.indexrelid
                    join pg_inherits ii on ii.inhrelid = ci.oid
                    where ti.inhparent = to_regclass(?)::oid
                      and ii.inhparent = to_regclass(?)::oid
                    """, "\"" + parent + "\"", "\"" + parent + "_cover\"").getValues(0, String.class);
            long partitions = dsl().fetchOne(
                    "select count(*) from pg_inherits where inhparent = to_regclass(?)::oid",
                    "\"" + parent + "\"").get(0, Long.class);
            assertThat(partitions).as(parent + " partitions").isGreaterThan(0);
            assertThat(childDefs).as(parent + " cover index children").hasSize((int) partitions);
            assertThat(childDefs).allMatch(d -> d.contains("INCLUDE"));
        }
    }

    @Test
    void level0_key_query_is_an_index_only_scan() throws Exception {
        List<String> plan = new ArrayList<>();
        try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("set enable_seqscan = off");
            s.execute("set enable_bitmapscan = off");
            try (ResultSet rs = s.executeQuery("""
                    explain select "occurredAt","itemId" from "wqWebItem"
                    where "userId" = 1
                      and "occurredAt" >= timestamptz '2036-07-01T00:00:00Z'
                      and "occurredAt" < timestamptz '2036-08-01T00:00:00Z'
                      and "productId" = 11 and not "cancelled"
                    order by "occurredAt" desc, "itemId" desc limit 10
                    """)) {
                while (rs.next()) plan.add(rs.getString(1));
            }
        }
        String text = String.join("\n", plan);
        assertThat(text).contains("Index Only Scan");
        String indexName = plan.stream()
                .filter(l -> l.contains("Index Only Scan"))
                .map(l -> l.replaceAll(".*Index Only Scan (?:Backward )?using (\\S+) on .*", "$1"))
                .findFirst().orElseThrow().replace("\"", "");
        assertThat(indexName).doesNotContain("pkey");
        Boolean isCoverChild = dsl().fetchOne("""
                select exists (
                  select 1 from pg_inherits i
                  where i.inhrelid = to_regclass(?)::oid
                    and i.inhparent = to_regclass('"wqWebItem_cover"')::oid)
                """, "\"" + indexName + "\"").get(0, Boolean.class);
        assertThat(isCoverChild).as("scanned index %s in plan:%n%s", indexName, text).isTrue();
    }
}
