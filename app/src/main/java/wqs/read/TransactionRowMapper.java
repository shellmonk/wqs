package wqs.read;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps one "wqPrjTransaction" row of the current result-set position to its wire entry; one per endpoint. */
@FunctionalInterface
public interface TransactionRowMapper {

    TransactionEntry toEntry(ResultSet rs) throws SQLException;
}
