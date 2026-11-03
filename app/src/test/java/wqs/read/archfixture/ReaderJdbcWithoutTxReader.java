package wqs.read.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.config.ReaderJdbc;

/**
 * Fixture: injects the reader client but no {@code @ReaderTx} template, so its statements would
 * autocommit, one snapshot each (must be rejected).
 */
public class ReaderJdbcWithoutTxReader {
    public ReaderJdbcWithoutTxReader(@ReaderJdbc JdbcClient jdbc) {}
}
