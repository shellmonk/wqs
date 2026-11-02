package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards spec section 8.3: generated code must read like the schema. The database is
 * camelCase and fully quoted, so jOOQ's default strategy -- which assumes snake_case and
 * runs a toCamelCase conversion over every name -- flattens the humps: Wqwebitem,
 * Tables.WQWEBITEM, getOccurredat(). AsInDatabaseStrategy preserves them, and these
 * assertions read the real generated classes to prove it.
 *
 * <p>Two of these are load-bearing for later tasks. Tables.WQ_WEB_ITEM is the constant every
 * query in the plan names. getOccurredAt() must be a JavaBean accessor -- capital first
 * letter -- or MapStruct (spec section 2.3) will not see it as a property and will generate a
 * mapper that compiles while mapping nothing.
 */
class GeneratedCodeTest {

    /** A Postgres range-partition child, e.g. "wqWebItem_2026_11". */
    private static final Pattern PARTITION_CHILD = Pattern.compile(".*_\\d{4}_\\d{2}$");

    @Test
    void table_fields_read_like_the_schema() throws Exception {
        Class<?> table = Class.forName("wqs.db.generated.tables.WqWebItem");

        List<String> fields = fieldNames(table);

        assertThat(fields).contains("userId", "occurredAt", "subBatchId", "productId");
        assertThat(fields).doesNotContain("OCCURREDAT", "OCCURRED_AT", "Occurredat");
    }

    @Test
    void table_constant_is_upper_snake() throws Exception {
        Class<?> tables = Class.forName("wqs.db.generated.Tables");

        assertThat(fieldNames(tables)).contains("WQ_WEB_ITEM");

        Object wqWebItem = tables.getField("WQ_WEB_ITEM").get(null);
        assertThat(wqWebItem).isInstanceOf(Class.forName("wqs.db.generated.tables.WqWebItem"));
    }

    @Test
    void sequence_constant_is_upper_snake() throws Exception {
        Class<?> sequences = Class.forName("wqs.db.generated.Sequences");

        assertThat(fieldNames(sequences)).contains("WQ_ITEM_ID_SEQ");
    }

    @Test
    void record_accessors_are_javabean_conventional() throws Exception {
        Class<?> record = Class.forName("wqs.db.generated.tables.records.WqWebItemRecord");

        assertThatCode(() -> record.getMethod("getOccurredAt")).doesNotThrowAnyException();
        assertThatCode(() -> record.getMethod("getSubBatchId")).doesNotThrowAnyException();
    }

    @Test
    void partition_children_and_infrastructure_are_excluded() throws Exception {
        assertThat(classExists("wqs.db.generated.tables.WqWebItem_2026_11")).isFalse();
        assertThat(classExists("wqs.db.generated.tables.FlywaySchemaHistory")).isFalse();

        List<String> generatedTables = fieldNames(Class.forName("wqs.db.generated.Tables"));

        assertThat(generatedTables).isNotEmpty();
        assertThat(generatedTables).noneMatch(name -> PARTITION_CHILD.matcher(name).matches());
        assertThat(generatedTables).doesNotContain("FLYWAY_SCHEMA_HISTORY");
        assertThat(generatedTables).noneMatch(name -> name.startsWith("QRTZ"));
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getFields()).map(Field::getName).toList();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
