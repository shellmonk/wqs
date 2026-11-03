package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationTest extends PostgresTestBase {

    @Test
    void item_tables_exist_and_are_range_partitioned() {
        List<String> partitioned = db().column("""
                       select c.relname
                       from pg_class c
                       join pg_namespace n on n.oid = c.relnamespace
                       where n.nspname = 'public'
                         and c.relkind = 'p'
                       order by c.relname
                       """, String.class);

        assertThat(partitioned).contains("wqWebItem", "wqCcItem");
    }

    @Test
    void locator_is_not_partitioned_and_keyed_by_source_and_sourceId() {
        String relkind = db().value("select relkind::text from pg_class where relname = 'wqItemLocator'", String.class);

        // 'r' = ordinary table. A global one-probe lookup requires it NOT be partitioned (spec 5.4).
        assertThat(relkind).isEqualTo("r");

        List<String> pkColumns = db().column("""
                       select a.attname
                       from pg_index i
                       join pg_class c on c.oid = i.indrelid
                       join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
                       where c.relname = 'wqItemLocator' and i.indisprimary
                       order by a.attname
                       """, String.class);

        assertThat(pkColumns).containsExactly("source", "sourceId");
    }

    @Test
    void camelcase_identifiers_survived_migration() {
        List<String> columns = db().column("""
                       select column_name
                       from information_schema.columns
                       where table_name = 'wqWebItem'
                       order by column_name
                       """, String.class);

        // Unquoted DDL would have folded these to lowercase.
        assertThat(columns).contains("occurredAt", "subBatchId", "userId");
    }

    // --- Final schema (baseline migration) ---------------------------------------------------

    private static final List<String> PRODUCT_TABLES = List.of(
            "wqWebItem", "wqCcItem", "wqSession", "wqPlay", "wqWebHighLevel", "wqCcHighLevel",
            "wqPendingCancel");

    @Test
    void products_are_text_and_there_is_no_product_table() {
        for (String table : PRODUCT_TABLES) {
            assertThat(columnType(table, "productId")).as(table + ".productId").isEqualTo("text");
            assertThat(columnType(table, "productType")).as(table + ".productType").isEqualTo("text");
        }
        assertThat(db().value("select to_regclass('\"wqProduct\"')::text", String.class))
                .as("no product table").isNull();
    }

    @Test
    void counter_pk_is_user_view_unit_productType_typeId_period() {
        for (String table : List.of("wqCountMonth", "wqCountDay")) {
            List<String> pk = db().column("""
                    select a.attname
                    from pg_index i
                    join pg_class c on c.oid = i.indrelid
                    cross join lateral unnest(i.indkey) with ordinality as k(attnum, pos)
                    join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
                    where c.relname = ? and i.indisprimary
                    order by k.pos
                    """, String.class, table);
            assertThat(pk).as(table + " primary key").containsExactly(
                    "userId", "viewKind", "pageUnit", "productType", "typeId", "period");
            assertThat(columnType(table, "productType")).as(table).isEqualTo("text");
            assertThat(columnType(table, "typeId")).as(table).isEqualTo("smallint");
            assertThat(columnType(table, "productId")).as(table + " has no productId").isNull();
        }
        // wqCountDay is range-partitioned by "period"; wqCountMonth is not partitioned.
        assertThat(db().value("""
                select pg_get_partkeydef(to_regclass('"wqCountDay"')::oid)
                """, String.class)).isEqualTo("RANGE (period)");
        assertThat(db().value("select relkind::text from pg_class where relname = 'wqCountMonth'", String.class))
                .isEqualTo("r");
        for (String table : List.of("wqCountMonth", "wqCountDay")) {
            assertThat(db().value("""
                    select count(*) from pg_constraint
                    where conrelid = to_regclass(?)::oid and contype = 'c'
                    """, Long.class, "\"" + table + "\"")).as(table + " checks").isPositive();
        }
    }

    @Test
    void tx_types_are_seeded_and_the_identity_continues_after_them() {
        Map<String, Short> direction = db().rows("select \"code\", \"direction\" from \"wqTxType\"").stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> r.get("code", String.class), r -> r.get("direction", Short.class)));
        // MoneyDirection codes: 0 = NEUTRAL, 1 = PLAY, 2 = WIN.
        assertThat(direction).hasSizeGreaterThanOrEqualTo(50);
        assertThat(direction).containsEntry("TT_BUY", (short) 1)
                .containsEntry("BUY", (short) 1)
                .containsEntry("BET", (short) 1)
                .containsEntry("TT_PERIODIC_CHARGE", (short) 1)
                .containsEntry("TT_PRIZE_IN", (short) 2)
                .containsEntry("PRIZE", (short) 2)
                .containsEntry("TT_TRANSFER_OUT", (short) 0)
                .containsEntry("TT_BUY_UNDO", (short) 0)
                .containsEntry("TT_PRIZE_ROLLBACK", (short) 0);
        assertThat(direction.values().stream().filter(d -> d != 0)).hasSize(6);
        assertThat(db().value("select min(\"typeId\") from \"wqTxType\"", Short.class))
                .as("real type ids start at 1; 0 is the ALL sentinel").isEqualTo((short) 1);
        // The seed sets the identity past its explicit ids (other tests may register more codes).
        assertThat(db().value("""
                select last_value from pg_sequences
                where schemaname = 'public' and sequencename = 'wqTxType_typeId_seq'
                """, Long.class)).isGreaterThanOrEqualTo(56L);
    }

    /** D8: the locator-only columns and the CHECK that "cancels" appears only on locator-only rows. */
    @Test
    void locator_carries_zero_amount_and_cancels_with_their_check() {
        assertThat(columnType("wqItemLocator", "zeroAmount")).isEqualTo("boolean");
        assertThat(nullable("wqItemLocator", "zeroAmount")).isFalse();
        assertThat(columnType("wqItemLocator", "cancels")).isEqualTo("text");
        assertThat(db().value("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = 'wqItemLocator_cancels_locator_only'
                """, String.class)).contains("cancels IS NULL").contains("\"zeroAmount\"");
        for (String table : List.of("wqSession", "wqPlay")) {
            assertThat(columnType(table, "firstSourceId")).as(table).isEqualTo("text");
            assertThat(columnType(table, "firstSource")).as(table).isEqualTo("smallint");
        }
    }

    /** Covering indexes INCLUDE the filter columns; the session-member indexes are on the batch id. */
    @Test
    void read_path_indexes_have_their_final_shape() {
        assertThat(indexDef("wqWebItem_cover")).endsWith(
                "(\"userId\", \"occurredAt\" DESC, \"itemId\" DESC) INCLUDE (cancelled, \"productType\", type)");
        assertThat(indexDef("wqCcItem_cover")).endsWith(
                "(\"userId\", \"occurredAt\" DESC, \"itemId\" DESC) INCLUDE (cancelled, \"productType\", type)");
        assertThat(indexDef("wqWebHighLevel_cover")).endsWith(
                "(\"userId\", \"occurredAt\" DESC, kind DESC, \"refId\" DESC) INCLUDE (\"productType\", \"visibleExclCancelled\")");
        assertThat(indexDef("wqCcHighLevel_cover")).endsWith(
                "(\"userId\", \"occurredAt\" DESC, kind DESC, \"refId\" DESC) INCLUDE (\"productType\", \"visibleExclCancelled\")");
        assertThat(indexDef("wqWebItem_batch")).endsWith(
                "(\"userId\", \"batchId\", \"occurredAt\") WHERE (\"batchId\" IS NOT NULL)");
        assertThat(indexDef("wqCcItem_batch")).endsWith(
                "(\"userId\", \"batchId\", \"occurredAt\") WHERE (\"batchId\" IS NOT NULL)");
        assertThat(indexDef("wqPlay_batch")).endsWith("(\"userId\", \"batchId\", \"startedAt\")");
    }

    /** The Quartz JobStore lives in its own schema. */
    @Test
    void quartz_tables_live_in_the_quartz_schema() {
        assertThat(db().value("""
                select count(*) from pg_tables where schemaname = 'quartz' and tablename like 'qrtz\\_%'
                """, Long.class)).isEqualTo(11L);
    }

    /** D5: the versioned-upsert column and the delta poller's watermark table. */
    @Test
    void locator_has_versionAt_and_watermark_table_exists() {
        assertThat(columnType("wqItemLocator", "versionAt")).isEqualTo("timestamp with time zone");
        assertThat(nullable("wqItemLocator", "versionAt")).as("null for stream items").isTrue();

        assertThat(columnType("wqIngestWatermark", "source")).isEqualTo("text");
        assertThat(columnType("wqIngestWatermark", "watermark")).isEqualTo("timestamp with time zone");
        assertThat(columnType("wqIngestWatermark", "updatedAt")).isEqualTo("timestamp with time zone");
        assertThat(nullable("wqIngestWatermark", "watermark")).isFalse();
        assertThat(nullable("wqIngestWatermark", "updatedAt")).isFalse();
        List<String> pk = db().column("""
                select a.attname from pg_index i
                join pg_class c on c.oid = i.indrelid
                join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
                where c.relname = 'wqIngestWatermark' and i.indisprimary
                """, String.class);
        assertThat(pk).containsExactly("source");
        String updatedAtDefault = db().value("""
                select column_default from information_schema.columns
                where table_schema = 'public' and table_name = 'wqIngestWatermark' and column_name = 'updatedAt'
                """, String.class);
        assertThat(updatedAtDefault).isEqualTo("now()");
    }

    private static String indexDef(String name) {
        return db().value("select pg_get_indexdef(to_regclass(?)::oid)", String.class, "\"" + name + "\"");
    }

    private static boolean nullable(String table, String column) {
        return "YES".equals(db().value("""
                select is_nullable from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, String.class, table, column));
    }

    private static String columnType(String table, String column) {
        List<String> types = db().column("""
                select data_type from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, String.class, table, column);
        return types.isEmpty() ? null : types.get(0);
    }
}
