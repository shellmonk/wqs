package wqs.projection;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The UTC months in which an owner ("ownerType", "userId") has at least one stored transaction:
 * the months a cancel's target can lie in (plan D3). A cancel names its target by (source,
 * sourceId) with no timestamp, so the target lookup is bounded to these months instead of probing
 * every monthly partition.
 *
 * <p>Derived from the owner's "wqPrjCount" rows: every stored transaction is counted at the
 * TRANSACTION page unit on its own UTC day, and those rows are never deleted (a cancel lowers only
 * the excl-cancelled tally), so the distinct months of the owner's days are exactly the months that
 * hold their transactions. The writer counts a transaction before any later cancel in the same batch
 * looks it up, so a target inserted earlier in the batch is found too.
 *
 * <p>This is the one place that knows where the months come from: the owner-month table
 * ("wqOwnerMonth", plan Task 4) replaces the source without changing callers.
 */
@Component
public class OwnerMonths {

    private static final String MONTHS = """
            select distinct cast(date_trunc('month', cast("day" as timestamp)) as date) as "month"
            from "wqPrjCount"
            where "ownerType" = ? and "userId" = ?
            order by "month"
            """;

    private final JdbcClient jdbc;

    public OwnerMonths(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The owner's months, ascending; empty if the owner has no stored transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<YearMonth> of(short ownerType, long userId) {
        return jdbc.sql(MONTHS)
                .params(ownerType, userId)
                .query((rs, n) -> YearMonth.from(rs.getObject("month", LocalDate.class)))
                .list();
    }
}
