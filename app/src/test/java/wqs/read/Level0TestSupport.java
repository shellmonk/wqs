package wqs.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.config.ReaderDsl;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionPropertyBase;

@SpringBootTest
abstract class Level0TestSupport extends ProjectionPropertyBase {

    protected static final long USER = 7001L;

    @Autowired @ReaderDsl protected DSLContext readerDsl;

    protected static ProjectionItem item(
            SourceKind source, String id, String at, String product, String cancels, String detail) {
        return new ProjectionItem(USER, source, id, Instant.parse(at), product,
                product == null ? null : "keno", null, null, TxTypes.PLAY,
                new BigDecimal("1.0000"), "EUR", null, cancels, detail);
    }

    protected void project(ProjectionItem... items) {
        writer.apply(new ProjectionBatch("wallet-transactions", List.of(items), 1L));
    }
}
