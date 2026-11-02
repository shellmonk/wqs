package wqs.read.archfixture;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderDsl;

/** Fixture: @Autowired field without @ReaderDsl (must be rejected). */
public class UnqualifiedInjectedFieldReader {
    @Autowired
    private DSLContext injectedDsl;
}
