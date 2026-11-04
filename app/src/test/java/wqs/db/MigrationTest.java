package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationTest extends PostgresTestBase {

    @Test
    void only_the_transactions_are_range_partitioned() {
        List<String> partitioned = db().column("""
                       select c.relname
                       from pg_class c
                       join pg_namespace n on n.oid = c.relnamespace
                       where n.nspname = 'public'
                         and c.relkind = 'p'
                       order by c.relname
                       """, String.class);

        assertThat(partitioned).containsExactly("wqPrjTransaction");
    }

    /**
     * The owner-month model's final schema: seven tables and no views. Endpoint knowledge is not in the
     * data -- no per-endpoint view, no view kind, no "onWeb" -- and the page index, the per-day counters
     * and the locator are gone.
     */
    @Test
    void the_schema_is_eight_tables_and_no_views() {
        List<String> tables = db().column("""
                select c.relname from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind in ('r', 'p') and not c.relispartition
                  and c.relname <> 'flyway_schema_history'
                order by c.relname
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder("wqPrjTransaction", "wqPrjSession", "wqPrjPlay", "wqOwnerMonth",
                "wqPrjPendingCancel", "wqPrjPendingZero", "wqIngestCursor", "wqDeadLetter");
        assertThat(db().column("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind in ('v', 'm')
                """, String.class)).as("no views").isEmpty();
        for (String gone : List.of("wqPrjTimeline", "wqPrjCount", "wqPrjWebTransaction", "wqPrjCcTransaction",
                "wqPrjTransactionLocator")) {
            assertThat(relkind(gone)).as(gone + " is gone").isNull();
        }
        assertThat(db().column("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public' and column_name in ('viewKind', 'onWeb', 'pageUnit')
                """, String.class)).as("no endpoint columns").isEmpty();
    }

    /**
     * Levels 1-2 page sessions merged with standalone rows (plan D4): a session's visibility with
     * cancelled excluded is generated from its surviving-transaction count, one flag for every policy.
     */
    @Test
    void sessions_carry_one_generated_visibility() {
        assertThat(columnType("wqPrjSession", "visibleExclCancelled")).isEqualTo("boolean");
        assertThat(nullable("wqPrjSession", "visibleExclCancelled")).isFalse();
        assertThat(db().value("""
                select generation_expression from information_schema.columns
                where table_name = 'wqPrjSession' and column_name = 'visibleExclCancelled'
                """, String.class)).contains("\"txCountExclCancelled\" > 0");
    }

    /** Owner identity is ("ownerType", "userId"): the owner type leads every owner key and index. */
    @Test
    void owner_type_leads_every_owner_key() {
        for (String table : List.of("wqPrjTransaction", "wqPrjSession", "wqPrjPlay", "wqOwnerMonth", "wqPrjPendingZero")) {
            assertThat(primaryKey(table)).as(table).startsWith("ownerType", "userId");
        }
        for (String table : List.of("wqPrjTransaction", "wqPrjPendingCancel",
                "wqPrjSession", "wqPrjPlay", "wqOwnerMonth")) {
            assertThat(columnType(table, "ownerType")).as(table).isEqualTo("smallint");
            assertThat(nullable(table, "ownerType")).as(table).isFalse();
        }
    }

    /** Plan D5: sessions and plays are keyed by batch, not partitioned, and need no group locator. */
    @Test
    void sessions_and_plays_are_unpartitioned_and_keyed_by_batch() {
        assertThat(relkind("wqPrjSession")).isEqualTo("r");
        assertThat(relkind("wqPrjPlay")).isEqualTo("r");
        assertThat(primaryKey("wqPrjSession")).containsExactly("ownerType", "userId", "batchId");
        assertThat(primaryKey("wqPrjPlay")).containsExactly("ownerType", "userId", "batchId", "subBatchId");
        assertThat(relkind("wqPrjSession_startedAt")).as("one session time index: the reading index").isNull();
        assertThat(relkind("wqPrjGroupLocator")).as("no group locator").isNull();
        assertThat(relkind("wqPrjPlay_batch")).as("the play primary key serves the batch lookup").isNull();
    }

    private static List<String> primaryKey(String table) {
        return db().column("""
                select a.attname
                from pg_index i
                join pg_class c on c.oid = i.indrelid
                cross join lateral unnest(i.indkey) with ordinality as k(attnum, pos)
                join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
                where c.relname = ? and i.indisprimary
                order by k.pos
                """, String.class, table);
    }

    private static String relkind(String name) {
        return db().column("select relkind::text from pg_class where relname = ?", String.class, name)
                .stream().findFirst().orElse(null);
    }

    /**
     * Plan D2: no locator; duplicates are caught by the transactions' own unique key
     * ("source", "sourceId", "occurredAt"), which holds the partition key and so exists on every partition.
     */
    @Test
    void duplicates_are_caught_by_a_unique_key_on_the_transactions_and_there_is_no_locator() {
        assertThat(relkind("wqPrjTransactionLocator")).as("no locator").isNull();
        assertThat(db().column("""
                select a.attname
                from pg_index i
                join pg_class c on c.oid = i.indrelid
                cross join lateral unnest(i.indkey) with ordinality as k(attnum, pos)
                join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
                where c.relname = 'wqPrjTransaction' and i.indisunique and not i.indisprimary
                order by k.pos
                """, String.class)).containsExactly("source", "sourceId", "occurredAt");
        assertThat(db().value("""
                select count(*) from pg_inherits h
                join pg_index i on i.indrelid = h.inhrelid and i.indisunique and not i.indisprimary
                where h.inhparent = to_regclass('"wqPrjTransaction"')
                """, Long.class)).as("on every partition").isEqualTo(db().value("""
                select count(*) from pg_inherits where inhparent = to_regclass('"wqPrjTransaction"')
                """, Long.class));
    }

    @Test
    void camelcase_identifiers_survived_migration() {
        List<String> columns = db().column("""
                       select column_name
                       from information_schema.columns
                       where table_name = 'wqPrjTransaction'
                       order by column_name
                       """, String.class);

        // Unquoted DDL would have folded these to lowercase.
        assertThat(columns).contains("occurredAt", "subBatchId", "userId");
    }

    // --- Final schema (baseline migration) ---------------------------------------------------

    private static final List<String> PRODUCT_TABLES = List.of(
            "wqPrjTransaction", "wqPrjSession", "wqPrjPlay", "wqPrjPendingCancel");

    @Test
    void products_are_text_and_there_is_no_product_table() {
        for (String table : PRODUCT_TABLES) {
            assertThat(columnType(table, "productId")).as(table + ".productId").isEqualTo("text");
            assertThat(columnType(table, "productType")).as(table + ".productType").isEqualTo("text");
        }
        assertThat(db().value("select to_regclass('\"wqProduct\"')::text", String.class))
                .as("no product table").isNull();
    }

    /**
     * Plan D5: one owner-month count table, keyed by owner, month and the exact combination; not
     * partitioned. The month is a first-of-month date.
     */
    @Test
    void owner_month_key_is_owner_month_and_exact_combination() {
        assertThat(primaryKey("wqOwnerMonth")).containsExactly(
                "ownerType", "userId", "month", "source", "unit", "productType", "type", "zeroAmount");
        assertThat(relkind("wqOwnerMonth")).as("not partitioned").isEqualTo("r");
        assertThat(columnType("wqOwnerMonth", "month")).isEqualTo("date");
        assertThat(columnType("wqOwnerMonth", "source")).isEqualTo("smallint");
        assertThat(columnType("wqOwnerMonth", "unit")).isEqualTo("smallint");
        assertThat(columnType("wqOwnerMonth", "productType")).isEqualTo("text");
        assertThat(columnType("wqOwnerMonth", "cnt")).isEqualTo("bigint");
        assertThat(columnType("wqOwnerMonth", "cntExclCancelled")).isEqualTo("bigint");
        assertThat(columnType("wqOwnerMonth", "productId")).as("has no productId").isNull();
        assertThat(db().value("""
                select count(*) from pg_constraint
                where conrelid = to_regclass('"wqOwnerMonth"')::oid and contype = 'c'
                """, Long.class)).as("checks").isGreaterThanOrEqualTo(3);
        for (String gone : List.of("wqPrjCount", "wqPrjCountMonth", "wqPrjCountDay")) {
            assertThat(relkind(gone)).as(gone + " is gone").isNull();
        }
    }

    /** Plan D3: a parked cancel names its target by ("source", "targetSourceId"). */
    @Test
    void a_parked_cancel_names_its_target() {
        assertThat(primaryKey("wqPrjPendingCancel")).containsExactly("source", "cancelSourceId");
        assertThat(columnType("wqPrjPendingCancel", "targetSourceId")).isEqualTo("text");
        assertThat(nullable("wqPrjPendingCancel", "targetSourceId")).isFalse();
        assertThat(columnType("wqPrjPendingCancel", "parkedAt")).isEqualTo("timestamp with time zone");
    }

    /** Plan D1: transaction types are a Java enum; every "type" column holds the code as text. */
    @Test
    void type_columns_are_text_and_there_is_no_type_table() {
        for (String table : List.of("wqPrjTransaction", "wqPrjPendingCancel",
                "wqOwnerMonth")) {
            assertThat(columnType(table, "type")).as(table + ".type").isEqualTo("text");
            assertThat(nullable(table, "type")).as(table + ".type").isFalse();
        }
        assertThat(db().value("select to_regclass('\"wqTxType\"')::text", String.class))
                .as("no type table").isNull();
    }

    /**
     * Store everything: "zeroAmount" is a stored generated column of the transactions.
     */
    @Test
    void zero_amount_is_a_generated_transaction_column() {
        assertThat(columnType("wqPrjTransaction", "zeroAmount")).isEqualTo("boolean");
        assertThat(db().value("""
                select generation_expression from information_schema.columns
                where table_name = 'wqPrjTransaction' and column_name = 'zeroAmount'
                """, String.class)).contains("amount = (0)").contains("source = 1");
        for (String table : List.of("wqPrjSession", "wqPrjPlay")) {
            assertThat(columnType(table, "zeroTxCount")).as(table).isEqualTo("integer");
            assertThat(nullable(table, "zeroTxCount")).as(table).isFalse();
        }
        for (String table : List.of("wqPrjSession", "wqPrjPlay")) {
            assertThat(columnType(table, "firstSourceId")).as(table).isEqualTo("text");
            assertThat(columnType(table, "firstSource")).as(table).isEqualTo("smallint");
        }
    }

    /**
     * The final read-path indexes: the level-0 cover, the standalone-row partial index and the session
     * reading index (merged at levels 1-2, plan D4), and the session-member index.
     */
    @Test
    void read_path_indexes_have_their_final_shape() {
        assertThat(indexDef("wqPrjTransaction_cover")).endsWith(
                "(\"ownerType\", \"userId\", \"occurredAt\" DESC, \"transactionId\" DESC) INCLUDE (source, \"productType\", type, cancelled, \"zeroAmount\")");
        assertThat(indexDef("wqPrjTransaction_standalone")).endsWith(
                "(\"ownerType\", \"userId\", \"occurredAt\" DESC, source DESC, \"sourceId\" DESC) INCLUDE (\"productType\", cancelled, \"zeroAmount\")"
                        + " WHERE ((\"batchId\" IS NULL) AND (cancels IS NULL))");
        assertThat(indexDef("wqPrjTransaction_batch")).endsWith(
                "(\"ownerType\", \"userId\", \"batchId\", \"occurredAt\") WHERE (\"batchId\" IS NOT NULL)");
        assertThat(indexDef("wqPrjSession_reading")).endsWith(
                "(\"ownerType\", \"userId\", \"startedAt\" DESC, \"batchId\" DESC) INCLUDE (\"productType\", \"visibleExclCancelled\")");
    }

    /** The Quartz JobStore lives in its own schema. */
    @Test
    void quartz_tables_live_in_the_quartz_schema() {
        assertThat(db().value("""
                select count(*) from pg_tables where schemaname = 'quartz' and tablename like 'qrtz\\_%'
                """, Long.class)).isEqualTo(11L);
    }

    /** D5: the versioned-upsert column; one ingest cursor row per feed (stream offset or delta watermark). */
    @Test
    void transactions_have_versionAt_and_the_ingest_cursor_has_its_shape() {
        assertThat(columnType("wqPrjTransaction", "versionAt")).isEqualTo("timestamp with time zone");
        assertThat(nullable("wqPrjTransaction", "versionAt")).as("null for stream transactions").isTrue();

        assertThat(columnType("wqIngestCursor", "feed")).isEqualTo("text");
        assertThat(columnType("wqIngestCursor", "streamOffset")).isEqualTo("bigint");
        assertThat(columnType("wqIngestCursor", "watermark")).isEqualTo("timestamp with time zone");
        assertThat(columnType("wqIngestCursor", "updatedAt")).isEqualTo("timestamp with time zone");
        assertThat(nullable("wqIngestCursor", "streamOffset")).as("null for the delta feed").isTrue();
        assertThat(nullable("wqIngestCursor", "watermark")).as("null for a stream feed").isTrue();
        assertThat(nullable("wqIngestCursor", "updatedAt")).isFalse();
        List<String> pk = db().column("""
                select a.attname from pg_index i
                join pg_class c on c.oid = i.indrelid
                join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
                where c.relname = 'wqIngestCursor' and i.indisprimary
                """, String.class);
        assertThat(pk).containsExactly("feed");
        String updatedAtDefault = db().value("""
                select column_default from information_schema.columns
                where table_schema = 'public' and table_name = 'wqIngestCursor' and column_name = 'updatedAt'
                """, String.class);
        assertThat(updatedAtDefault).isEqualTo("now()");
        for (String gone : List.of("wqStreamOffset", "wqIngestWatermark")) {
            assertThat(db().value("select to_regclass(?)::text", String.class, "\"" + gone + "\""))
                    .as(gone + " is merged into wqIngestCursor").isNull();
        }
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
