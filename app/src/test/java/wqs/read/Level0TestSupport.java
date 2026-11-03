package wqs.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.config.ReaderJdbc;
import wqs.config.ReaderTx;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionBatch;
import wqs.projection.ProjectionPropertyBase;

@SpringBootTest
abstract class Level0TestSupport extends ProjectionPropertyBase {

    protected static final long USER = 7001L;

    @Autowired @ReaderJdbc protected JdbcClient readerJdbc;
    @Autowired @ReaderTx protected TransactionTemplate readerTx;

    protected static ProjectionTransaction transaction(
            SourceKind source, String id, String at, String product, String cancels, String detail) {
        return new ProjectionTransaction(USER, source, id, Instant.parse(at), product,
                product == null ? null : "keno", null, null, TxTypes.PLAY,
                new BigDecimal("1.0000"), "EUR", null, cancels, detail);
    }

    protected void project(ProjectionTransaction... transactions) {
        writer.apply(new ProjectionBatch("wallet-transactions", List.of(transactions), 1L));
    }
}
