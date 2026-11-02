package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationTest extends PostgresTestBase {

    @Test
    void item_tables_exist_and_are_range_partitioned() {
        List<String> partitioned = dsl()
                .fetch("""
                       select c.relname
                       from pg_class c
                       join pg_namespace n on n.oid = c.relnamespace
                       where n.nspname = 'public'
                         and c.relkind = 'p'
                       order by c.relname
                       """)
                .getValues(0, String.class);

        assertThat(partitioned).contains("wqWebItem", "wqCcItem");
    }

    @Test
    void locator_is_not_partitioned_and_keyed_by_source_and_sourceId() {
        String relkind = dsl()
                .fetchOne("select relkind::text from pg_class where relname = 'wqItemLocator'")
                .get(0, String.class);

        // 'r' = ordinary table. A global one-probe lookup requires it NOT be partitioned (spec 5.4).
        assertThat(relkind).isEqualTo("r");

        List<String> pkColumns = dsl()
                .fetch("""
                       select a.attname
                       from pg_index i
                       join pg_class c on c.oid = i.indrelid
                       join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
                       where c.relname = 'wqItemLocator' and i.indisprimary
                       order by a.attname
                       """)
                .getValues(0, String.class);

        assertThat(pkColumns).containsExactly("source", "sourceId");
    }

    @Test
    void camelcase_identifiers_survived_migration() {
        List<String> columns = dsl()
                .fetch("""
                       select column_name
                       from information_schema.columns
                       where table_name = 'wqWebItem'
                       order by column_name
                       """)
                .getValues(0, String.class);

        // Unquoted DDL would have folded these to lowercase.
        assertThat(columns).contains("occurredAt", "subBatchId", "userId");
    }
}
