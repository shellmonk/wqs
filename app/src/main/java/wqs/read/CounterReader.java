package wqs.read;

import static wqs.db.generated.Tables.WQ_COUNT_DAY;
import static wqs.db.generated.Tables.WQ_COUNT_MONTH;
import static wqs.domain.CounterSentinels.ALL_PRODUCT_TYPES;
import static wqs.domain.CounterSentinels.ALL_TYPES;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.impl.DSL;
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

    public Map<LocalDate, Long> monthCounts(
            DSLContext tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<Short> typeIds, boolean excludeCancelled,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        return read(tx, WQ_COUNT_MONTH, WQ_COUNT_MONTH.userId, WQ_COUNT_MONTH.viewKind,
                WQ_COUNT_MONTH.pageUnit, WQ_COUNT_MONTH.productType, WQ_COUNT_MONTH.typeId,
                WQ_COUNT_MONTH.period,
                excludeCancelled ? WQ_COUNT_MONTH.cntExclCancelled : WQ_COUNT_MONTH.cntAll,
                userId, view, unit, productTypes, typeIds, firstPeriod, lastPeriod);
    }

    public Map<LocalDate, Long> dayCounts(
            DSLContext tx, long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<Short> typeIds, boolean excludeCancelled,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        return read(tx, WQ_COUNT_DAY, WQ_COUNT_DAY.userId, WQ_COUNT_DAY.viewKind,
                WQ_COUNT_DAY.pageUnit, WQ_COUNT_DAY.productType, WQ_COUNT_DAY.typeId,
                WQ_COUNT_DAY.period,
                excludeCancelled ? WQ_COUNT_DAY.cntExclCancelled : WQ_COUNT_DAY.cntAll,
                userId, view, unit, productTypes, typeIds, firstPeriod, lastPeriod);
    }

    private static <R extends Record> Map<LocalDate, Long> read(
            DSLContext tx, Table<R> table,
            TableField<R, Long> userCol, TableField<R, Short> viewCol, TableField<R, Short> unitCol,
            TableField<R, String> productCol, TableField<R, Short> typeCol,
            TableField<R, LocalDate> periodCol, TableField<R, Long> countCol,
            long userId, ViewKind view, PageUnit unit,
            Set<String> productTypes, Set<Short> typeIds,
            LocalDate firstPeriod, LocalDate lastPeriod) {
        Set<String> products = productTypes.isEmpty() ? Set.of(ALL_PRODUCT_TYPES) : productTypes;
        Set<Short> types = typeIds.isEmpty() ? Set.of(ALL_TYPES) : typeIds;
        Result<Record2<LocalDate, Long>> rows = tx.select(periodCol, DSL.sum(countCol).cast(Long.class))
                .from(table)
                .where(userCol.eq(userId))
                .and(viewCol.eq((short) view.code()))
                .and(unitCol.eq(unit.code()))
                .and(productCol.in(products))
                .and(typeCol.in(types))
                .and(periodCol.between(firstPeriod, lastPeriod))
                .groupBy(periodCol)
                .fetch();
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }
}
