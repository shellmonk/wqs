package wqs.read.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import wqs.domain.SourceKind;
import wqs.read.ItemEntry;
import wqs.read.TypeCodes;

/** Maps one "wqWebItem" row of the current result-set position to its wire entry. */
@Component
public class WebItemMapper {

    /** The row's entry; its "type" id resolves through {@code codes}. */
    public ItemEntry toEntry(ResultSet rs, TypeCodes codes) throws SQLException {
        OffsetDateTime occurredAt = rs.getObject("occurredAt", OffsetDateTime.class);
        return new ItemEntry(
                SourceKind.ofCode(rs.getShort("source")).name(),
                rs.getString("sourceId"),
                occurredAt == null ? null : occurredAt.toInstant(),
                rs.getString("productId"),
                rs.getString("productType"),
                rs.getString("batchId"),
                rs.getString("subBatchId"),
                codes.codeOf(rs.getShort("type")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getBigDecimal("balanceAfter"),
                rs.getBoolean("cancelled"),
                rs.getString("cancelledBy"),
                rs.getString("cancels"),
                rs.getString("detail"));
    }
}
