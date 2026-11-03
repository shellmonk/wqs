package wqs.read;

import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.config.ReaderJdbc;
import wqs.config.ReaderTx;
import wqs.domain.ViewKind;
import wqs.projection.ProjectionPropertyBase;
import wqs.read.cc.CcHighLevelQuery;
import wqs.read.cc.CcLevel0Query;
import wqs.read.web.WebHighLevelQuery;
import wqs.read.web.WebLevel0Query;

/** Shared setup for the read-side properties (spec 8.1): reader, both views' queries, the oracle. */
public abstract class ReadPropertyBase extends ProjectionPropertyBase {

    @Autowired @ReaderJdbc protected JdbcClient readerJdbc;
    @Autowired @ReaderTx protected TransactionTemplate readerTx;
    @Autowired protected ProjectionReader reader;
    @Autowired protected WebLevel0Query webQuery;
    @Autowired protected CcLevel0Query ccQuery;
    @Autowired protected WebHighLevelQuery webHighLevelQuery;
    @Autowired protected CcHighLevelQuery ccHighLevelQuery;

    protected final NaiveOffsetOracle oracle = new NaiveOffsetOracle(jdbc());

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

    protected HighLevelQuery highLevelQueryFor(ViewKind view) {
        return switch (view) {
            case WEB -> webHighLevelQuery;
            case CC -> ccHighLevelQuery;
        };
    }

    /** Both views' page-index queries, as the reader's constructor takes them. */
    protected List<HighLevelQuery> highLevelQueries() {
        return List.of(webHighLevelQuery, ccHighLevelQuery);
    }
}
