package wqs.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The UTC months in which an owner ("ownerType", "userId") has stored transactions, read from the
 * owner's TX rows in "wqOwnerMonth" (plan D3). Every stored transaction is counted at TX in its own
 * month, and TX rows are never deleted (a cancel lowers only the excl-cancelled tally), so these are
 * exactly the months that hold the owner's transactions. The writer counts a transaction before any
 * later transaction in the same batch looks it up, so a row inserted earlier in the batch is seen too.
 *
 * <p>The one place that knows where the months come from. Its callers bound a lookup that has no
 * timestamp to these months instead of probing every monthly partition: a cancel's target (named by
 * (source, sourceId) only), and a new group's earlier zero-amount rows.
 */
@Component
public class OwnerMonths {

    private static final String MONTHS = """
            select distinct "month" from "wqOwnerMonth"
            where "ownerType" = ? and "userId" = ? and "unit" = ?
            order by "month"
            """;
    private static final String ZERO_AMOUNT_MONTHS = """
            select distinct "month" from "wqOwnerMonth"
            where "ownerType" = ? and "userId" = ? and "unit" = ? and "zeroAmount"
            order by "month"
            """;

    private static final short TX = wqs.domain.PageUnit.TRANSACTION.code();

    private final JdbcClient jdbc;

    public OwnerMonths(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The owner's months, ascending; empty if the owner has no stored transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<YearMonth> of(short ownerType, long userId) {
        return months(MONTHS, ownerType, userId);
    }

    /** The months holding the owner's zero-amount transactions, ascending; usually empty. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<YearMonth> withZeroAmounts(short ownerType, long userId) {
        return months(ZERO_AMOUNT_MONTHS, ownerType, userId);
    }

    private List<YearMonth> months(String sql, short ownerType, long userId) {
        return jdbc.sql(sql)
                .params(ownerType, userId, TX)
                .query((rs, n) -> YearMonth.from(rs.getObject("month", LocalDate.class)))
                .list();
    }

    /**
     * The months as two parallel arrays of UTC instants, [start, next start), each ISO-8601 text: the
     * binds of an {@code unnest(cast(? as timestamptz[]), cast(? as timestamptz[]))} month probe.
     */
    static String[][] bounds(List<YearMonth> months) {
        String[] fromAt = new String[months.size()];
        String[] toAt = new String[months.size()];
        for (int i = 0; i < months.size(); i++) {
            fromAt[i] = start(months.get(i)).toString();
            toAt[i] = start(months.get(i).plusMonths(1)).toString();
        }
        return new String[][] {fromAt, toAt};
    }

    private static Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
