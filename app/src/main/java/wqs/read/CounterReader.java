package wqs.read;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.domain.PageUnit;

/**
 * Reads the owner-month counts ("wqOwnerMonth", plan D5). Sparse: an absent month means zero.
 *
 * Counts are stored per EXACT combination (source, product type, type, zero-amount flag), so a total
 * for product types P and type codes T is the sum of the rows whose product type is in P (any, when P
 * is empty) and whose type is in T (any, when T is empty) -- exact because every row has one of each.
 * HIGH_LEVEL (ENTRY) callers always pass an empty {@code typeCodes}: entries carry no type.
 *
 * The read policy (plan D1) picks the owner type, the sources and whether zero-amount rows count.
 */
@Component
public class CounterReader {

    // The count column and the filter fragments come from fixed constants only; every value is bound.
    private static final String SUM = """
            select "month", cast(sum("%s") as bigint) as "cnt" from "wqOwnerMonth"
            where ("ownerType" = :ownerType and "userId" = :userId and "unit" = :unit
                   and "month" between cast(:first as date) and cast(:last as date)
                   and "source" = any (cast(:sources as smallint[]))
                   and (cast(:includeZeroAmounts as boolean) or not "zeroAmount")%s%s)
            group by "month"
            """;
    private static final String PRODUCT_TYPES = " and \"productType\" in (:productTypes)";
    private static final String TYPE_CODES = " and \"type\" in (:typeCodes)";

    /**
     * The count of every month in [first, last] that has a row (absent month = 0), summed over the
     * combinations the filters select. One index range on the primary key's
     * ("ownerType", "userId", "month") prefix, whatever the number of months.
     */
    public Map<YearMonth, Long> monthCounts(
            JdbcClient tx, ReadPolicy policy, long userId, PageUnit unit,
            Set<String> productTypes, Set<String> typeCodes, boolean excludeCancelled,
            YearMonth first, YearMonth last) {
        Map<YearMonth, Long> out = new LinkedHashMap<>();
        JdbcClient.StatementSpec spec = policy.bind(tx.sql(SUM.formatted(
                        excludeCancelled ? "cntExclCancelled" : "cnt",
                        productTypes.isEmpty() ? "" : PRODUCT_TYPES,
                        typeCodes.isEmpty() ? "" : TYPE_CODES)))
                .param("userId", userId)
                .param("unit", unit.code())
                .param("first", first.atDay(1))
                .param("last", last.atDay(1));
        if (!productTypes.isEmpty()) {
            spec = spec.param("productTypes", productTypes);
        }
        if (!typeCodes.isEmpty()) {
            spec = spec.param("typeCodes", typeCodes);
        }
        spec.query(rs -> {
            out.put(YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("cnt"));
        });
        return out;
    }
}
