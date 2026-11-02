package wqs.read.archfixture;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderDsl;

/** Fixture: unqualified method parameter (must be rejected). */
public class UnqualifiedMethodParamReader {
    public void run(DSLContext methodDsl) {}
}
