package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

class MigrationTest extends PostgresTestBase {

    @Test
    void transaction_tables_exist_and_are_range_partitioned() {
        List<String> partitioned = db().column("""
                       select c.relname
                       from pg_class c
                       join pg_namespace n on n.oid = c.relnamespace
                       where n.nspname = 'public'
                         and c.relkind = 'p'
                       order by c.relname
                       """, String.class);

        assertThat(partitioned).contains("wqPrjTransaction", "wqPrjTimeline")
                .doesNotContain("wqPrjWebTransaction", "wqPrjCcTransaction", "wqPrjWebHighLevel", "wqPrjCcHighLevel");
    }

    /**
     * Each endpoint reads a view over the one "wqPrjTransaction" table. /web's holds exactly the
     * sources whose {@link SourceKind#views()} include WEB -- the routing and the schema cannot
     * drift apart -- and /cc's holds every row.
     */
    @Test
    void each_endpoint_has_a_transaction_view_with_its_own_sources() {
        String webCodes = java.util.Arrays.stream(SourceKind.values())
                .filter(k -> k.views().contains(ViewKind.WEB))
                .map(k -> String.valueOf(k.code()))
                .collect(java.util.stream.Collectors.joining(", "));
        assertThat(relkind("wqPrjWebTransaction")).isEqualTo("v");
        assertThat(relkind("wqPrjCcTransaction")).isEqualTo("v");
        assertThat(viewDef("wqPrjWebTransaction")).contains("FROM \"wqPrjTransaction\"")
                .endsWith("WHERE (source = ANY (ARRAY[" + webCodes + "]));");
        assertThat(viewDef("wqPrjCcTransaction")).contains("FROM \"wqPrjTransaction\"").doesNotContain("WHERE");
        assertThat(relkind("wqPrjWebHighLevel")).as("no per-view page index").isNull();
        assertThat(relkind("wqPrjCcHighLevel")).as("no per-view page index").isNull();
    }

    /**
     * One page index for both endpoints: "onWeb" marks /web's rows and one "visibleExclCancelled"
     * serves both, because a session never mixes sources.
     */
    @Test
    void the_timeline_carries_on_web_and_one_visibility() {
        for (String column : List.of("onWeb", "visibleExclCancelled")) {
            assertThat(columnType("wqPrjTimeline", column)).as(column).isEqualTo("boolean");
            assertThat(nullable("wqPrjTimeline", column)).as(column).isFalse();
        }
        assertThat(columnType("wqPrjTimeline", "webVisibleExclCancelled")).as("no per-endpoint visibility").isNull();
    }

    /** Owner identity is ("ownerType", "userId"): the owner type leads every owner key and index. */
    @Test
    void owner_type_leads_every_owner_key() {
        for (String table : List.of("wqPrjTransaction", "wqPrjSession", "wqPrjPlay", "wqPrjTimeline", "wqPrjCount")) {
            assertThat(primaryKey(table)).as(table).startsWith("ownerType", "userId");
        }
        for (String table : List.of("wqPrjTransaction", "wqPrjTransactionLocator", "wqPrjPendingCancel",
                "wqPrjSession", "wqPrjPlay", "wqPrjTimeline", "wqPrjCount")) {
            assertThat(columnType(table, "ownerType")).as(table).isEqualTo("smallint");
            assertThat(nullable(table, "ownerType")).as(table).isFalse();
        }
        assertThat(indexDef("wqPrjTransactionLocator_user_idx")).endsWith("(\"ownerType\", \"userId\")");
    }

    /** Plan D5: sessions and plays are keyed by batch, not partitioned, and need no group locator. */
    @Test
    void sessions_and_plays_are_unpartitioned_and_keyed_by_batch() {
        assertThat(relkind("wqPrjSession")).isEqualTo("r");
        assertThat(relkind("wqPrjPlay")).isEqualTo("r");
        assertThat(primaryKey("wqPrjSession")).containsExactly("ownerType", "userId", "batchId");
        assertThat(primaryKey("wqPrjPlay")).containsExactly("ownerType", "userId", "batchId", "subBatchId");
        assertThat(relkind("wqPrjSession_startedAt")).as("no session time index: paging reads the timeline").isNull();
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

    private static String viewDef(String name) {
        return db().value("select pg_get_viewdef(to_regclass(?)::oid)", String.class, "\"" + name + "\"").strip();
    }

    @Test
    void locator_is_not_partitioned_and_keyed_by_source_and_sourceId() {
        String relkind = db().value("select relkind::text from pg_class where relname = 'wqPrjTransactionLocator'", String.class);

        // 'r' = ordinary table. A global one-probe lookup requires it NOT be partitioned (spec 5.4).
        assertThat(relkind).isEqualTo("r");

        List<String> pkColumns = db().column("""
                       select a.attname
                       from pg_index i
                       join pg_class c on c.oid = i.indrelid
                       join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
                       where c.relname = 'wqPrjTransactionLocator' and i.indisprimary
                       order by a.attname
                       """, String.class);

        assertThat(pkColumns).containsExactly("source", "sourceId");
    }

    @Test
    void camelcase_identifiers_survived_migration() {
        List<String> columns = db().column("""
                       select column_name
                       from information_schema.columns
                       where table_name = 'wqPrjWebTransaction'
                       order by column_name
                       """, String.class);

        // Unquoted DDL would have folded these to lowercase.
        assertThat(columns).contains("occurredAt", "subBatchId", "userId");
    }

    // --- Final schema (baseline migration) ---------------------------------------------------

    private static final List<String> PRODUCT_TABLES = List.of(
            "wqPrjTransaction", "wqPrjWebTransaction", "wqPrjCcTransaction", "wqPrjSession", "wqPrjPlay",
            "wqPrjTimeline", "wqPrjPendingCancel");

    @Test
    void products_are_text_and_there_is_no_product_table() {
        for (String table : PRODUCT_TABLES) {
            assertThat(columnType(table, "productId")).as(table + ".productId").isEqualTo("text");
            assertThat(columnType(table, "productType")).as(table + ".productType").isEqualTo("text");
        }
        assertThat(db().value("select to_regclass('\"wqProduct\"')::text", String.class))
                .as("no product table").isNull();
    }

    /** Plan D3: one per-day counter table, monthly partitioned on "day"; no month tier. */
    @Test
    void counter_pk_is_user_view_unit_productType_type_day() {
        for (String table : List.of("wqPrjCount")) {
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
                    "ownerType", "userId", "viewKind", "pageUnit", "productType", "type", "zeroAmount", "day");
            assertThat(columnType(table, "productType")).as(table).isEqualTo("text");
            assertThat(columnType(table, "type")).as(table).isEqualTo("text");
            assertThat(columnType(table, "productId")).as(table + " has no productId").isNull();
        }
        assertThat(columnType("wqPrjCount", "day")).isEqualTo("date");
        assertThat(db().value("""
                select pg_get_partkeydef(to_regclass('"wqPrjCount"')::oid)
                """, String.class)).isEqualTo("RANGE (day)");
        for (String gone : List.of("wqPrjCountMonth", "wqPrjCountDay")) {
            assertThat(relkind(gone)).as(gone + " is gone").isNull();
        }
        for (String table : List.of("wqPrjCount")) {
            assertThat(db().value("""
                    select count(*) from pg_constraint
                    where conrelid = to_regclass(?)::oid and contype = 'c'
                    """, Long.class, "\"" + table + "\"")).as(table + " checks").isPositive();
        }
    }

    /** Plan D1: transaction types are a Java enum; every "type" column holds the code as text. */
    @Test
    void type_columns_are_text_and_there_is_no_type_table() {
        for (String table : List.of("wqPrjTransaction", "wqPrjPendingCancel",
                "wqPrjCount")) {
            assertThat(columnType(table, "type")).as(table + ".type").isEqualTo("text");
            assertThat(nullable(table, "type")).as(table + ".type").isFalse();
        }
        assertThat(db().value("select to_regclass('\"wqTxType\"')::text", String.class))
                .as("no type table").isNull();
    }

    /**
     * Store everything: the locator has no locator-only columns any more (every transaction has its
     * row), and carries the owner type; "zeroAmount" is a stored generated column of the transactions.
     */
    @Test
    void zero_amount_is_a_generated_transaction_column_and_the_locator_carries_the_owner_type() {
        assertThat(columnType("wqPrjTransactionLocator", "zeroAmount")).as("no locator-only rows").isNull();
        assertThat(columnType("wqPrjTransactionLocator", "cancels")).isNull();
        assertThat(columnType("wqPrjTransactionLocator", "ownerType")).isEqualTo("smallint");
        assertThat(nullable("wqPrjTransactionLocator", "ownerType")).isFalse();
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

    /** Covering indexes INCLUDE the filter columns; the session-member indexes are on the batch id. */
    @Test
    void read_path_indexes_have_their_final_shape() {
        assertThat(indexDef("wqPrjTransaction_cover")).endsWith(
                "(\"ownerType\", \"userId\", \"occurredAt\" DESC, \"transactionId\" DESC) INCLUDE (cancelled, \"productType\", type, source, \"zeroAmount\")");
        assertThat(indexDef("wqPrjTimeline_cover")).endsWith(
                "(\"ownerType\", \"userId\", \"occurredAt\" DESC, kind DESC, \"refId\" DESC) INCLUDE (\"productType\", \"visibleExclCancelled\", \"onWeb\")");
        assertThat(indexDef("wqPrjTransaction_batch")).endsWith(
                "(\"ownerType\", \"userId\", \"batchId\", \"occurredAt\") WHERE (\"batchId\" IS NOT NULL)");
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
    void locator_has_versionAt_and_the_ingest_cursor_has_its_shape() {
        assertThat(columnType("wqPrjTransactionLocator", "versionAt")).isEqualTo("timestamp with time zone");
        assertThat(nullable("wqPrjTransactionLocator", "versionAt")).as("null for stream transactions").isTrue();

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
