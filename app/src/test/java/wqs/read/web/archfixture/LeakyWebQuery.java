package wqs.read.web.archfixture;

import static wqs.db.generated.Tables.WQ_CC_ITEM;

import org.jooq.DSLContext;

/** Fixture: a web-layer class reaching the operator table (must be rejected by the arch rule). */
public class LeakyWebQuery {
    public int leak(DSLContext tx) {
        return tx.selectFrom(WQ_CC_ITEM).fetch().size();
    }
}
