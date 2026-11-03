package wqs.read.archfixture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.config.ReaderJdbc;

/** Fixture: an injected reader-client field beside an UNqualified (writer) template (must be rejected). */
public class ReaderJdbcFieldWithoutTxReader {
    @Autowired
    @ReaderJdbc
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate writerTx;
}
