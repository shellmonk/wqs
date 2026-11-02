package wqs.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionPropertyBase;

/** Endpoint tests over MockMvc; fixtures are projected through the real writer. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ApiTestBase extends ProjectionPropertyBase {

    protected static final String FROM = "2034-02-01T00:00:00Z";
    protected static final String TO = "2034-03-01T00:00:00Z";
    protected static final String PRINCIPAL_HEADER = "X-Authenticated-User-Id";

    @Autowired protected MockMvc mvc;

    /** Empties the projection; every fixture lives in 2034-02. */
    @Override
    protected void reset() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 1), 2);
        clearProjection();
    }

    protected static ProjectionItem item(long userId, SourceKind source, String sourceId, String at) {
        return new ProjectionItem(userId, source, sourceId, Instant.parse(at), "P10", "instants", null, null,
                TxTypes.PLAY,
                new BigDecimal("12.5000"), "EUR", null, null, "{\"note\":\"" + sourceId + "\"}");
    }

    protected void project(ProjectionItem... items) {
        writer.apply(new ProjectionBatch("wallet-transactions", List.of(items), 1L));
    }
}
