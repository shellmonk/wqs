package wqs.read;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Test-only JDBC-level statement recorder over the reader pool: {@link #client()} is a
 * {@link JdbcClient} whose statements join the current reader transaction (as the app's
 * {@code @ReaderJdbc} client does) and are recorded with their binds, so a test can count them
 * and re-run each one under {@code explain}. Replaces the jOOQ {@code ExecuteListener} the
 * read-path tests used.
 */
final class StatementRecorder {

    /** One bind call on a prepared statement ({@code setLong(1, 7)}, {@code setObject(2, x)}, ...). */
    record Bind(Method setter, Object[] args) {}

    /** One executed statement: its SQL text as sent and the binds it ran with. */
    record Executed(String sql, List<Bind> binds) {}

    private static final Set<String> EXECUTE = Set.of(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");

    private final DataSource reader;
    private final JdbcClient client;
    private final List<Executed> executed = new ArrayList<>();

    StatementRecorder(DataSource reader) {
        this.reader = reader;
        DataSource joining = new TransactionAwareDataSourceProxy(reader);
        DataSource recording = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, m, args) -> {
                    Object r = invoke(joining, m, args);
                    return r instanceof Connection c && m.getName().equals("getConnection") ? connection(c) : r;
                });
        this.client = JdbcClient.create(recording);
    }

    JdbcClient client() {
        return client;
    }

    List<Executed> executed() {
        return executed;
    }

    int count() {
        return executed.size();
    }

    /**
     * Runs {@code prefix + " " + e.sql()} (e.g. {@code "explain"}) with {@code e}'s binds on a
     * fresh reader connection, returning the first column of every row.
     */
    List<String> rerun(String prefix, Executed e) throws SQLException {
        try (Connection c = reader.getConnection();
             PreparedStatement ps = c.prepareStatement(prefix + " " + e.sql())) {
            for (Bind b : e.binds()) {
                try {
                    b.setter().invoke(ps, b.args());
                } catch (IllegalAccessException | InvocationTargetException ex) {
                    throw new SQLException("replaying " + b.setter().getName(), ex);
                }
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, m, args) -> {
                    Object r = invoke(target, m, args);
                    if (r instanceof PreparedStatement ps && m.getName().startsWith("prepare")) {
                        return statement(ps, PreparedStatement.class, (String) args[0]);
                    }
                    if (r instanceof Statement s && m.getName().equals("createStatement")) {
                        return statement(s, Statement.class, null);
                    }
                    return r;
                });
    }

    private Object statement(Statement target, Class<? extends Statement> type, String preparedSql) {
        List<Bind> binds = new ArrayList<>();
        InvocationHandler h = (proxy, m, args) -> {
            String name = m.getName();
            if (preparedSql != null && name.startsWith("set") && args != null && args.length >= 2
                    && args[0] instanceof Integer) {
                binds.add(new Bind(m, args.clone()));
            } else if (name.equals("clearParameters")) {
                binds.clear();
            } else if (EXECUTE.contains(name)) {
                String sql = preparedSql != null ? preparedSql : args == null ? "" : (String) args[0];
                executed.add(new Executed(sql, List.copyOf(binds)));
            }
            return invoke(target, m, args);
        };
        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, h);
    }

    private static Object invoke(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
