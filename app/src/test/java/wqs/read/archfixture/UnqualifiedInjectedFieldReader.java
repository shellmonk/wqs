package wqs.read.archfixture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixture: @Autowired field without @ReaderJdbc (must be rejected). */
public class UnqualifiedInjectedFieldReader {
    @Autowired
    private JdbcClient injectedJdbc;
}
