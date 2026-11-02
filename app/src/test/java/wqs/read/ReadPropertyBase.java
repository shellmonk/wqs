package wqs.read;

import java.time.YearMonth;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderDsl;
import wqs.domain.ViewKind;
import wqs.projection.ProjectionPropertyBase;
import wqs.read.cc.CcLevel0Query;
import wqs.read.web.WebLevel0Query;

/** Shared setup for the read-side properties (spec 8.1): reader, both views' queries, the oracle. */
public abstract class ReadPropertyBase extends ProjectionPropertyBase {

    @Autowired @ReaderDsl protected DSLContext readerDsl;
    @Autowired protected ProjectionReader reader;
    @Autowired protected WebLevel0Query webQuery;
    @Autowired protected CcLevel0Query ccQuery;

    protected final NaiveOffsetOracle oracle = new NaiveOffsetOracle(dsl());

    /** Empties the projection; ReadGenerators' data and queries span 2033-12 through 2034-03. */
    @Override
    protected void reset() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2033, 12), 3);
        clearProjection();
    }

    protected Level0Query queryFor(ViewKind view) {
        return switch (view) {
            case WEB -> webQuery;
            case CC -> ccQuery;
        };
    }
}
