package wqs.api.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixture: an api class querying the database itself (must be rejected). */
public class JdbcClientController {
    public Object leak(JdbcClient jdbc) {
        return jdbc.sql("select 1").query().singleValue();
    }
}
