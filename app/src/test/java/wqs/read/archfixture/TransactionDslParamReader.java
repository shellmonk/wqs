package wqs.read.archfixture;

import org.jooq.DSLContext;

/** Fixture: a plain method receiving the caller's transaction DSL (must pass; not an injection point). */
public class TransactionDslParamReader {
    public void run(DSLContext tx) {}
}
