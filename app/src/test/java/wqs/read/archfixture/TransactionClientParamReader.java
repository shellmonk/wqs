package wqs.read.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixture: a plain method receiving the caller's transaction client (must pass; not an injection point). */
public class TransactionClientParamReader {
    public void run(JdbcClient tx) {}
}
