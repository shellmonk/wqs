package wqs.db;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plain-SQL helpers for test setup and assertions, over a {@link JdbcClient}: positional
 * {@code ?} binds, no named-parameter parsing. Values read into maps and lines are normalised
 * so that equal stored state compares equal: timestamptz as a UTC {@link OffsetDateTime}, date
 * as {@link LocalDate}, smallint as {@link Short}, jsonb/json as its text, everything else as
 * the driver returns it.
 *
 * <p>Statements run in the caller's Spring transaction on the same DataSource if there is one,
 * else in autocommit (so {@code vacuum} works).
 */
public final class TestSql {

    private final JdbcClient jdbc;

    public TestSql(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TestSql(DataSource dataSource) {
        this(JdbcClient.create(dataSource));
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** Runs a statement; returns its update count. */
    public int execute(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }

    /** The first column of the ONE row the query returns; the value itself may be null. */
    public <T> T value(String sql, Class<T> type, Object... args) {
        List<T> values = column(sql, type, args);
        if (values.size() != 1) {
            throw new IllegalStateException("expected one row, got " + values.size() + ": " + sql);
        }
        return values.get(0);
    }

    /** The first column of every row. */
    public <T> List<T> column(String sql, Class<T> type, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, n) -> get(rs, 1, type)).list();
    }

    /** {@code count(*)} over {@code fromClause}, e.g. {@code "\"wqSession\" where ..."}. */
    public int count(String fromClause, Object... args) {
        return value("select count(*)::int from " + fromClause, Integer.class, args);
    }

    /** The ONE row the query returns. */
    public Row row(String sql, Object... args) {
        List<Row> rows = rows(sql, args);
        if (rows.size() != 1) {
            throw new IllegalStateException("expected one row, got " + rows.size() + ": " + sql);
        }
        return rows.get(0);
    }

    /** Every row. */
    public List<Row> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, n) -> new Row(map(rs))).list();
    }

    /** Every row, column label to normalised value, in column order. */
    public List<Map<String, Object>> maps(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, n) -> map(rs)).list();
    }

    /** Every row as one {@code label=value, ...} line, for order-sensitive comparisons. */
    public List<String> lines(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, n) -> line(rs)).list();
    }

    /**
     * A fetched row: normalised values by column label, read back with a numeric or temporal
     * conversion ({@code smallint} as Integer, timestamptz as Instant, ...).
     */
    public record Row(Map<String, Object> asMap) {

        public Object get(String label) {
            if (!asMap.containsKey(label)) {
                throw new IllegalArgumentException("no column " + label + " in " + asMap.keySet());
            }
            return asMap.get(label);
        }

        public <T> T get(String label, Class<T> type) {
            return convert(get(label), type);
        }

        @Override
        public String toString() {
            return asMap.toString();
        }
    }

    static <T> T convert(Object value, Class<T> type) {
        if (value == null || type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof Number n) {
            Object converted = type == Short.class ? (Object) n.shortValue()
                    : type == Integer.class ? (Object) n.intValue()
                    : type == Long.class ? (Object) n.longValue()
                    : type == java.math.BigDecimal.class ? new java.math.BigDecimal(n.toString())
                    : null;
            if (converted != null) {
                return type.cast(converted);
            }
        }
        if (value instanceof OffsetDateTime at && type == Instant.class) {
            return type.cast(at.toInstant());
        }
        throw new IllegalArgumentException("cannot read " + value.getClass().getName() + " as " + type.getName());
    }

    /** One column, read as {@code type}; an {@link Instant} is read through OffsetDateTime. */
    public static <T> T get(ResultSet rs, int column, Class<T> type) throws SQLException {
        if (type == Instant.class) {
            OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
            return type.cast(at == null ? null : at.toInstant());
        }
        if (type == Object.class) {
            return type.cast(value(rs, column));
        }
        return rs.getObject(column, type);
    }

    public static <T> T get(ResultSet rs, String column, Class<T> type) throws SQLException {
        return get(rs, rs.findColumn(column), type);
    }

    /** The row as label to normalised value, in column order. */
    public static Map<String, Object> map(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            row.put(md.getColumnLabel(i), value(rs, i));
        }
        return row;
    }

    /** The row as {@code label=value, ...}. */
    public static String line(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        StringJoiner line = new StringJoiner(", ");
        for (int i = 1; i <= md.getColumnCount(); i++) {
            line.add(md.getColumnLabel(i) + "=" + value(rs, i));
        }
        return line.toString();
    }

    /** One column's normalised value (see the class comment). */
    public static Object value(ResultSet rs, int column) throws SQLException {
        String type = rs.getMetaData().getColumnTypeName(column);
        Object value = switch (type) {
            case "timestamptz" -> rs.getObject(column, OffsetDateTime.class);
            case "date" -> rs.getObject(column, LocalDate.class);
            case "int2" -> rs.getObject(column, Short.class);
            case "json", "jsonb" -> rs.getString(column);
            default -> rs.getObject(column);
        };
        return rs.wasNull() ? null : value;
    }
}
