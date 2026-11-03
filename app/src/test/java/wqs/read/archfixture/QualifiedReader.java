package wqs.read.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.config.ReaderJdbc;
import wqs.config.ReaderTx;

/** Fixture: the normal pattern -- plain fields assigned from qualified constructor parameters (must pass). */
public class QualifiedReader {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public QualifiedReader(@ReaderJdbc JdbcClient jdbc, @ReaderTx TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void run(@ReaderJdbc JdbcClient other) {}
}
