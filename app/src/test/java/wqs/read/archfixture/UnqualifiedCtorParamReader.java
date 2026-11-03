package wqs.read.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixture: unqualified constructor parameter (must be rejected). */
public class UnqualifiedCtorParamReader {
    public UnqualifiedCtorParamReader(JdbcClient ctorJdbc) {}
}
