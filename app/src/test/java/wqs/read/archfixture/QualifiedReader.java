package wqs.read.archfixture;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderDsl;

/** Fixture: the normal pattern -- plain field assigned from a qualified constructor parameter (must pass). */
public class QualifiedReader {
    private final DSLContext dsl;

    public QualifiedReader(@ReaderDsl DSLContext dsl) {
        this.dsl = dsl;
    }

    public void run(@ReaderDsl DSLContext other) {}
}
