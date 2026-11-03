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
 * Reads the per-day page-unit counters ("wqPrjCount"). Sparse: an absent day means zero.
 *
 * A total for lists P and T is the sum of count(p, t) over P' x T' (plan D5), where P' is P, or
 * the ALL-product-types sentinel when P is empty, and T' is T, or the ALL-types sentinel when T is
 * empty. That is exact because every transaction has one product type and one type. HIGH_LEVEL callers
 * always pass an empty {@code typeCodes}: HIGH_LEVEL counters carry no type.
 */
@Component
public class CounterReader {

    // The count column comes from two fixed constants only; every value is bound.
    private static final String SUM = """
            select "day", cast(sum("%s") as bigint) as "cnt" from "wqPrjCount"
            where ("userId" = :userId and "viewKind" = :viewKind and "pageUnit" = :pageUnit
                   and "productType" in (:productTypes) and "type" in (:typeCodes)
                   and "day" between cast(:first as date) and cast(:last as date))
            group by "day"
            """;

    /**
     * The count of every day in [firstDay, lastDay] that has a row (absent day = 0), summed over
     * the product-type and type keys (plan D3).
     */
    public Map<LocalDate, Long> dayCounts(
            JdbcClient tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<String> typeCodes, boolean excludeCancelled,
            LocalDate firstDay, LocalDate lastDay) {
        Set<String> products = productTypes.isEmpty() ? Set.of(ALL_PRODUCT_TYPES) : productTypes;
        Set<String> types = typeCodes.isEmpty() ? Set.of(ALL_TYPES) : typeCodes;
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        tx.sql(SUM.formatted(excludeCancelled ? "cntExclCancelled" : "cntAll"))
                .param("userId", userId)
                .param("viewKind", (short) view.code())
                .param("pageUnit", unit.code())
                .param("productTypes", products)
                .param("typeCodes", types)
                .param("first", firstDay)
                .param("last", lastDay)
                .query(rs -> {
                    out.put(rs.getObject("day", LocalDate.class), rs.getLong("cnt"));
                });
        return out;
    }
}
