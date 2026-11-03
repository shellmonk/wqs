package wqs.read.archfixture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixture: unqualified parameter of an injection method (must be rejected). */
public class UnqualifiedMethodParamReader {
    @Autowired
    public void run(JdbcClient methodJdbc) {}
}
