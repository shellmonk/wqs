package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

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

    // --- V10: source contract (phase 5, spec 4.1, plan D1-D3) ---------------------------------

    private static final List<String> PRODUCT_TABLES = List.of(
            "wqWebItem", "wqCcItem", "wqSession", "wqPlay", "wqWebHighLevel", "wqCcHighLevel",
            "wqPendingCancel");

    @Test
    void v10_rekeys_products_to_text() {
        for (String table : PRODUCT_TABLES) {
            assertThat(columnType(table, "productId")).as(table + ".productId").isEqualTo("text");
            assertThat(columnType(table, "productType")).as(table + ".productType").isEqualTo("text");
        }
        assertThat(db().value("select to_regclass('\"wqProduct\"')::text", String.class))
                .as("wqProduct dropped").isNull();
    }

    @Test
    void v10_counter_pk_is_user_view_unit_productType_typeId_period() {
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
        // wqCountDay stays range-partitioned by "period" (R5).
        assertThat(db().value("""
                select pg_get_partkeydef(to_regclass('"wqCountDay"')::oid)
                """, String.class)).isEqualTo("RANGE (period)");
        // The counter CHECKs survive the rebuild.
        for (String table : List.of("wqCountMonth", "wqCountDay")) {
            assertThat(db().value("""
                    select count(*) from pg_constraint
                    where conrelid = to_regclass(?)::oid and contype = 'c'
                    """, Long.class, "\"" + table + "\"")).as(table + " checks").isPositive();
        }
    }

    @Test
    void v10_seeds_tx_types() {
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
    }

    /**
     * D1: V10 empties every projection table and the stream offset, so the stream re-ingests from
     * offset 0; dead letters are kept. Runs V1..V9 on a fresh database, seeds every table the way
     * phase 4 left it, then applies V10.
     */
    @Test
    void v10_resets_stream_offset_and_projection() {
        TestSql db = freshDatabase("wqsV10Reset");
        PGSimpleDataSource ds = dataSourceFor("wqsV10Reset");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("9").load().migrate();
        // Phase-4 provisioning, then one row in every projection table.
        new PartitionManager(JdbcClient.create(dataSourceFor("wqsV10Reset"))).ensurePartitionsAhead(YearMonth.of(2030, 1), 0);
        db.execute("""
                insert into "wqProduct" values (11, 'p11', 'P 11', 1);
                insert into "wqItemLocator" values (1, 's1', 7, '2030-01-05T00:00:00Z', 1);
                insert into "wqWebItem" ("userId","itemId","source","sourceId","occurredAt","productId","type","amount","currency")
                  values (7, 1, 1, 's1', '2030-01-05T00:00:00Z', 11, 1, 1, 'EUR');
                insert into "wqCcItem" ("userId","itemId","source","sourceId","occurredAt","productId","type","amount","currency")
                  values (7, 1, 1, 's1', '2030-01-05T00:00:00Z', 11, 1, 1, 'EUR');
                insert into "wqGroupLocator" values (7, 'b1', '', '2030-01-05T00:00:00Z');
                insert into "wqSession" ("userId","batchId","startedAt","endedAt","productId","currency")
                  values (7, 'b1', '2030-01-05T00:00:00Z', '2030-01-05T00:00:00Z', 11, 'EUR');
                insert into "wqPlay" ("userId","batchId","subBatchId","startedAt","endedAt","productId","currency")
                  values (7, 'b1', 'p1', '2030-01-05T00:00:00Z', '2030-01-05T00:00:00Z', 11, 'EUR');
                insert into "wqWebHighLevel" ("userId","occurredAt","kind","refId","productId","currency")
                  values (7, '2030-01-05T00:00:00Z', 0, 'b1', 11, 'EUR');
                insert into "wqCcHighLevel" ("userId","occurredAt","kind","refId","productId","currency")
                  values (7, '2030-01-05T00:00:00Z', 0, 'b1', 11, 'EUR');
                insert into "wqCountMonth" values (7, 1, 0, 0, '2030-01-01', 1, 1);
                insert into "wqCountDay" values (7, 1, 0, 0, '2030-01-05', 1, 1);
                insert into "wqPendingCancel" ("source","cancelSourceId","cancelsSourceId","userId","occurredAt","productId","type","amount","currency")
                  values (1, 'c1', 'x1', 7, '2030-01-05T00:00:00Z', 11, 2, 1, 'EUR');
                insert into "wqStreamOffset" ("stream","offsetVal") values ('wallet', 42);
                insert into "wqDeadLetter" ("payload","error","attempts") values ('{}', 'poison', 3);
                """);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        for (String table : List.of("wqItemLocator", "wqWebItem", "wqCcItem", "wqGroupLocator",
                "wqSession", "wqPlay", "wqWebHighLevel", "wqCcHighLevel", "wqCountMonth",
                "wqCountDay", "wqPendingCancel", "wqStreamOffset")) {
            assertThat(db.count("\"" + table + "\"")).as(table + " emptied").isZero();
        }
        assertThat(db.count("\"wqDeadLetter\"")).as("dead letters kept").isEqualTo(1);
    }

    /**
     * R5: V10 recreates "wqCountDay", which drops its partitions with the old table. The app's
     * existing provisioning must recreate them -- it checks each table separately, so the other
     * tables' surviving partitions do not hide the missing ones.
     */
    @Test
    void v10_count_day_partitions_are_reprovisioned_by_partition_manager() {
        TestSql db = freshDatabase("wqsV10Partitions");
        PGSimpleDataSource ds = dataSourceFor("wqsV10Partitions");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("9").load().migrate();
        PartitionManager manager = new PartitionManager(JdbcClient.create(dataSourceFor("wqsV10Partitions")));
        YearMonth month = YearMonth.of(2030, 3);
        manager.ensurePartitionsAhead(month, 0);
        assertThat(partitionsOf(db, "wqCountDay")).containsExactly("wqCountDay_2030_03");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        assertThat(partitionsOf(db, "wqCountDay")).as("dropped with the old table").isEmpty();
        assertThat(partitionsOf(db, "wqWebItem")).containsExactly("wqWebItem_2030_03");
        assertThat(manager.runwayMonths(month)).as("runway sees the gap").isZero();

        assertThat(manager.ensurePartitionsAhead(month, 0)).isEqualTo(1);

        assertThat(partitionsOf(db, "wqCountDay")).containsExactly("wqCountDay_2030_03");
        assertThat(manager.runwayMonths(month)).isEqualTo(1);
        db.execute("""
                insert into "wqCountDay"
                  ("userId","viewKind","pageUnit","productType","typeId","period","cntAll","cntExclCancelled")
                values (7, 1, 0, '', 0, date '2030-03-31', 1, 1)
                """);
        assertThat(db.count("\"wqCountDay_2030_03\"")).isEqualTo(1);
    }

    /** D5: the versioned-upsert column and the delta poller's watermark table. */
    @Test
    void v13_adds_versionAt_and_watermark_table() {
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

    private static List<String> partitionsOf(TestSql db, String parent) {
        return db.column("""
                select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
                where i.inhparent = to_regclass(?)::oid order by c.relname
                """, String.class, "\"" + parent + "\"");
    }

    /** A new, empty database in the shared container (dropped first if a previous run left it). */
    private static TestSql freshDatabase(String name) {
        db().execute("drop database if exists \"" + name + "\" with (force)");
        db().execute("create database \"" + name + "\"");
        return new TestSql(dataSourceFor(name));
    }

    private static PGSimpleDataSource dataSourceFor(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
