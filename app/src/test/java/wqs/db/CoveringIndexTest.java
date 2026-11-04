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

/**
 * Spec 7.7: the read paths scan covering indexes of "wqPrjTransaction" -- the level-0 cover, the
 * standalone-row index (levels 1-2) and the session-member index -- and they exist on every partition.
 */
class CoveringIndexTest extends PostgresTestBase {

    private static final List<String> INDEXES =
            List.of("wqPrjTransaction_cover", "wqPrjTransaction_standalone", "wqPrjTransaction_batch");

    @BeforeAll
    static void createPartition() {
        // Created AFTER migration, so this proves the index propagates to future partitions.
        // Empty partitions are left behind deliberately (no rows; later tests re-ensure them).
        new PartitionManager(jdbc()).ensurePartitionsAhead(YearMonth.of(2036, 7), 0);
    }

    @Test
    void every_transaction_partition_has_every_read_index() {
        String parent = "wqPrjTransaction";
        for (String index : INDEXES) {
            List<String> childDefs = db().column("""
                    select pg_get_indexdef(ci.oid)
                    from pg_inherits ti
                    join pg_index ix on ix.indrelid = ti.inhrelid
                    join pg_class ci on ci.oid = ix.indexrelid
                    join pg_inherits ii on ii.inhrelid = ci.oid
                    where ti.inhparent = to_regclass(?)::oid
                      and ii.inhparent = to_regclass(?)::oid
                    """, String.class, "\"" + parent + "\"", "\"" + index + "\"");
            long partitions = db().value(
                    "select count(*) from pg_inherits where inhparent = to_regclass(?)::oid", Long.class,
                    "\"" + parent + "\"");
            assertThat(partitions).as(parent + " partitions").isGreaterThan(0);
            assertThat(childDefs).as(index + " children").hasSize((int) partitions);
            if (!index.endsWith("_batch")) {
                assertThat(childDefs).allMatch(d -> d.contains("INCLUDE"));
            }
        }
    }

    /**
     * Spec 7.7 / plan D4: key columns give each read a total order (the tie-break on equal
     * "occurredAt"); INCLUDE carries what filtering and the read policy read -- "source" among them, so
     * /web's sources are checked inside an index-only scan.
     */
    @Test
    void cover_index_key_and_include_columns() {
        assertThat(def("wqPrjTransaction_cover")).endsWith(
                "(ownerType, userId, occurredAt DESC, transactionId DESC) INCLUDE (source, productType, type, cancelled, zeroAmount)");
        assertThat(def("wqPrjTransaction_standalone")).endsWith(
                "(ownerType, userId, occurredAt DESC, source DESC, sourceId DESC) INCLUDE (productType, cancelled, zeroAmount)"
                        + " WHERE ((batchId IS NULL) AND (cancels IS NULL))");
    }

    private static String def(String index) {
        return db().value("select pg_get_indexdef(to_regclass(?)::oid)", String.class, "\"" + index + "\"")
                .replace("\"", "");
    }

    /** Through /web's read policy: its "source" predicate is answered from the index too. */
    @Test
    void level0_key_query_is_an_index_only_scan() throws Exception {
        List<String> plan = new ArrayList<>();
        try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("set enable_seqscan = off");
            s.execute("set enable_bitmapscan = off");
            try (ResultSet rs = s.executeQuery("""
                    explain select "occurredAt","transactionId" from "wqPrjTransaction"
                    where "ownerType" = 1 and "userId" = 1 and "source" = any ('{1,3}'::smallint[]) and not "zeroAmount"
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
        assertThat(text).as("the policy's source predicate, inside the scan").contains("source");
        assertThat(text).as("pruned to July 2036").contains("wqPrjTransaction_2036_07").doesNotContain("_2036_08");
        String indexName = plan.stream()
                .filter(l -> l.contains("Index Only Scan"))
                .map(l -> l.replaceAll(".*Index Only Scan (?:Backward )?using (\\S+) on .*", "$1"))
                .findFirst().orElseThrow().replace("\"", "");
        assertThat(indexName).doesNotContain("pkey");
        Boolean isCoverChild = db().value("""
                select exists (
                  select 1 from pg_inherits i
                  where i.inhrelid = to_regclass(?)::oid
                    and i.inhparent = to_regclass('"wqPrjTransaction_cover"')::oid)
                """, Boolean.class, "\"" + indexName + "\"");
        assertThat(isCoverChild).as("scanned index %s in plan:%n%s", indexName, text).isTrue();
    }
}
