package wqs.read.archfixture;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderDsl;

/** Fixture: unqualified parameter of an injection method (must be rejected). */
public class UnqualifiedMethodParamReader {
    @Autowired
    public void run(DSLContext methodDsl) {}
}
