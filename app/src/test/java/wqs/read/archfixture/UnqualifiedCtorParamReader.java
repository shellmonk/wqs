package wqs.read.archfixture;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderDsl;

/** Fixture: unqualified constructor parameter (must be rejected). */
public class UnqualifiedCtorParamReader {
    public UnqualifiedCtorParamReader(DSLContext ctorDsl) {}
}
