package wqs.read;

import static wqs.domain.CounterSentinels.ALL_PRODUCT_TYPES;
import static wqs.domain.CounterSentinels.ALL_TYPES;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import wqs.domain.PageUnit;
import wqs.domain.ViewKind;

/**
 * Reads page-unit counters. Sparse: an absent period means zero.
 *
 * A total for lists P and T is the sum of count(p, t) over P' x T' (plan D5), where P' is P, or
 * the ALL-product-types sentinel when P is empty, and T' is T, or the ALL-types sentinel when T is
 * empty. That is exact because every transaction has one product type and one type. HIGH_LEVEL callers
 * always pass an empty {@code typeCodes}: HIGH_LEVEL counters carry no type.
 */
@Component
public class CounterReader {

    // Table and count column come from these fixed constants only; every value is bound.
    private static final String SUM = """
            select "period", cast(sum("%2$s") as bigint) as "cnt" from "%1$s"
            where ("userId" = :userId and "viewKind" = :viewKind and "pageUnit" = :pageUnit
                   and "productType" in (:productTypes) and "type" in (:typeCodes)
                   and "period" between cast(:first as date) and cast(:last as date))
            group by "period"
            """;

    public Map<LocalDate, Long> monthCounts(
            JdbcClient tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<String> typeCodes, boolean excludeCancelled,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        return read(tx, "wqPrjCountMonth", excludeCancelled,
                userId, view, unit, productTypes, typeCodes, firstPeriod, lastPeriod);
    }

    public Map<LocalDate, Long> dayCounts(
            JdbcClient tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<String> typeCodes, boolean excludeCancelled,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        return read(tx, "wqPrjCountDay", excludeCancelled,
                userId, view, unit, productTypes, typeCodes, firstPeriod, lastPeriod);
    }

    private static Map<LocalDate, Long> read(
            JdbcClient tx, String table, boolean excludeCancelled,
            long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<String> typeCodes,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        Set<String> products = productTypes.isEmpty() ? Set.of(ALL_PRODUCT_TYPES) : productTypes;
        Set<String> types = typeCodes.isEmpty() ? Set.of(ALL_TYPES) : typeCodes;
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        tx.sql(SUM.formatted(table, excludeCancelled ? "cntExclCancelled" : "cntAll"))
                .param("userId", userId)
                .param("viewKind", (short) view.code())
                .param("pageUnit", unit.code())
                .param("productTypes", products)
                .param("typeCodes", types)
                .param("first", firstPeriod)
                .param("last", lastPeriod)
                .query(rs -> {
                    out.put(rs.getObject("period", LocalDate.class), rs.getLong("cnt"));
                });
        return out;
    }
}
