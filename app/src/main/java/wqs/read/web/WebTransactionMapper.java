package wqs.read.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import wqs.domain.SourceKind;
import wqs.read.TransactionEntry;
import wqs.read.TransactionRowMapper;

/** Maps one "wqPrjTransaction" row of the current result-set position to its web wire entry. */
@Component
public class WebTransactionMapper implements TransactionRowMapper {

    /** The row's entry; "type" is the stored code (plan D1). */
    @Override
    public TransactionEntry toEntry(ResultSet rs) throws SQLException {
        OffsetDateTime occurredAt = rs.getObject("occurredAt", OffsetDateTime.class);
        return new TransactionEntry(
                SourceKind.ofCode(rs.getShort("source")).name(),
                rs.getString("sourceId"),
                occurredAt == null ? null : occurredAt.toInstant(),
                rs.getString("productId"),
                rs.getString("productType"),
                rs.getString("batchId"),
                rs.getString("subBatchId"),
                rs.getString("type"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getBigDecimal("balanceAfter"),
                rs.getBoolean("cancelled"),
                rs.getString("cancelledBy"),
                rs.getString("cancels"),
                rs.getString("detail"));
    }
}
