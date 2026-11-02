package wqs.read.archfixture;

import org.jooq.DSLContext;

/** Fixture the ReaderDsl rule must reject: an unqualified DSLContext in the read layer. */
public class UnqualifiedReader {
    private final DSLContext dsl;

    public UnqualifiedReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    public DSLContext dsl() {
        return dsl;
    }
}
