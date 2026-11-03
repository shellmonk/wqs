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
 * empty. That is exact because every item has one product type and one type. HIGH_LEVEL callers
 * always pass an empty {@code typeIds}: HIGH_LEVEL counters carry no type.
 */
@Component
public class CounterReader {

    // Table and count column come from these fixed constants only; every value is bound.
    private static final String SUM = """
            select "period", cast(sum("%2$s") as bigint) as "cnt" from "%1$s"
            where ("userId" = :userId and "viewKind" = :viewKind and "pageUnit" = :pageUnit
                   and "productType" in (:productTypes) and "typeId" in (:typeIds)
                   and "period" between cast(:first as date) and cast(:last as date))
            group by "period"
            """;

    public Map<LocalDate, Long> monthCounts(
            JdbcClient tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<Short> typeIds, boolean excludeCancelled,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        return read(tx, "wqCountMonth", excludeCancelled,
                userId, view, unit, productTypes, typeIds, firstPeriod, lastPeriod);
    }

    public Map<LocalDate, Long> dayCounts(
            JdbcClient tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<Short> typeIds, boolean excludeCancelled,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        return read(tx, "wqCountDay", excludeCancelled,
                userId, view, unit, productTypes, typeIds, firstPeriod, lastPeriod);
    }

    private static Map<LocalDate, Long> read(
            JdbcClient tx, String table, boolean excludeCancelled,
            long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<Short> typeIds,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        Set<String> products = productTypes.isEmpty() ? Set.of(ALL_PRODUCT_TYPES) : productTypes;
        Set<Short> types = typeIds.isEmpty() ? Set.of(ALL_TYPES) : typeIds;
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        tx.sql(SUM.formatted(table, excludeCancelled ? "cntExclCancelled" : "cntAll"))
                .param("userId", userId)
                .param("viewKind", (short) view.code())
                .param("pageUnit", unit.code())
                .param("productTypes", products)
                .param("typeIds", types)
                .param("first", firstPeriod)
                .param("last", lastPeriod)
                .query(rs -> {
                    out.put(rs.getObject("period", LocalDate.class), rs.getLong("cnt"));
                });
        return out;
    }
}
