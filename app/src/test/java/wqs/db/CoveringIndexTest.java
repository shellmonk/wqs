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
            List.of("wqPrjWebTransaction", "wqPrjCcTransaction", "wqPrjWebHighLevel", "wqPrjCcHighLevel");

    @BeforeAll
    static void createPartition() {
        // Created AFTER migration, so this proves the index propagates to future partitions.
        // Empty partitions are left behind deliberately (no rows; later tests re-ensure them).
        new PartitionManager(jdbc()).ensurePartitionsAhead(YearMonth.of(2036, 7), 0);
    }

    @Test
    void every_transaction_and_high_level_partition_has_the_cover_index() {
        for (String parent : PARENTS) {
            List<String> childDefs = db().column("""
                    select pg_get_indexdef(ci.oid)
                    from pg_inherits ti
                    join pg_index ix on ix.indrelid = ti.inhrelid
                    join pg_class ci on ci.oid = ix.indexrelid
                    join pg_inherits ii on ii.inhrelid = ci.oid
                    where ti.inhparent = to_regclass(?)::oid
                      and ii.inhparent = to_regclass(?)::oid
                    """, String.class, "\"" + parent + "\"", "\"" + parent + "_cover\"");
            long partitions = db().value(
                    "select count(*) from pg_inherits where inhparent = to_regclass(?)::oid", Long.class,
                    "\"" + parent + "\"");
            assertThat(partitions).as(parent + " partitions").isGreaterThan(0);
            assertThat(childDefs).as(parent + " cover index children").hasSize((int) partitions);
            assertThat(childDefs).allMatch(d -> d.contains("INCLUDE"));
        }
    }

    /**
     * Spec 5.6 / 7.7: key columns give each page index a total order (the tie-break on equal
     * "occurredAt"); INCLUDE carries only what filtering reads.
     */
    @Test
    void cover_index_key_and_include_columns() {
        String transactions = "(userId, occurredAt DESC, transactionId DESC) INCLUDE (cancelled, productType, type)";
        String highLevel = "(userId, occurredAt DESC, kind DESC, refId DESC) INCLUDE (productType, visibleExclCancelled)";
        for (String parent : PARENTS) {
            String def = db().value("select pg_get_indexdef(to_regclass(?)::oid)", String.class,
                    "\"" + parent + "_cover\"").replace("\"", "");
            assertThat(def).as(parent).endsWith(parent.endsWith("Transaction") ? transactions : highLevel);
        }
    }

    @Test
    void level0_key_query_is_an_index_only_scan() throws Exception {
        List<String> plan = new ArrayList<>();
        try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("set enable_seqscan = off");
            s.execute("set enable_bitmapscan = off");
            try (ResultSet rs = s.executeQuery("""
                    explain select "occurredAt","transactionId" from "wqPrjWebTransaction"
                    where "userId" = 1
                      and "occurredAt" >= timestamptz '2036-07-01T00:00:00Z'
                      and "occurredAt" < timestamptz '2036-08-01T00:00:00Z'
                      and "productType" = 'instants' and "type" = 'TT_BUY' and not "cancelled"
                    order by "occurredAt" desc, "transactionId" desc limit 10
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
        Boolean isCoverChild = db().value("""
                select exists (
                  select 1 from pg_inherits i
                  where i.inhrelid = to_regclass(?)::oid
                    and i.inhparent = to_regclass('"wqPrjWebTransaction_cover"')::oid)
                """, Boolean.class, "\"" + indexName + "\"");
        assertThat(isCoverChild).as("scanned index %s in plan:%n%s", indexName, text).isTrue();
    }
}
