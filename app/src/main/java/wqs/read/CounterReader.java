package wqs.read;

import static wqs.db.generated.Tables.WQ_COUNT_DAY;
import static wqs.db.generated.Tables.WQ_COUNT_MONTH;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.stereotype.Component;
import wqs.domain.PageUnit;
import wqs.domain.ViewKind;

/** Reads level-0 (ITEM) counters. Sparse: absent period means zero. */
@Component
public class CounterReader {

    public Map<LocalDate, Long> months(
            DSLContext tx, ViewKind v, Level0Filter f, LocalDate firstPeriod, LocalDate lastPeriod) {
        Field<Long> col = f.excludeCancelled() ? WQ_COUNT_MONTH.cntExclCancelled : WQ_COUNT_MONTH.cntAll;
        Result<Record2<LocalDate, Long>> rows = tx.select(WQ_COUNT_MONTH.period, col)
                .from(WQ_COUNT_MONTH)
                .where(WQ_COUNT_MONTH.userId.eq(f.userId()))
                .and(WQ_COUNT_MONTH.viewKind.eq((short) v.code()))
                .and(WQ_COUNT_MONTH.pageUnit.eq(PageUnit.ITEM.code()))
                .and(WQ_COUNT_MONTH.productId.eq(f.counterProductKey()))
                .and(WQ_COUNT_MONTH.period.between(firstPeriod, lastPeriod))
                .fetch();
        return toMap(rows);
    }

    public Map<LocalDate, Long> days(
            DSLContext tx, ViewKind v, Level0Filter f, LocalDate firstPeriod, LocalDate lastPeriod) {
        Field<Long> col = f.excludeCancelled() ? WQ_COUNT_DAY.cntExclCancelled : WQ_COUNT_DAY.cntAll;
        Result<Record2<LocalDate, Long>> rows = tx.select(WQ_COUNT_DAY.period, col)
                .from(WQ_COUNT_DAY)
                .where(WQ_COUNT_DAY.userId.eq(f.userId()))
                .and(WQ_COUNT_DAY.viewKind.eq((short) v.code()))
                .and(WQ_COUNT_DAY.pageUnit.eq(PageUnit.ITEM.code()))
                .and(WQ_COUNT_DAY.productId.eq(f.counterProductKey()))
                .and(WQ_COUNT_DAY.period.between(firstPeriod, lastPeriod))
                .fetch();
        return toMap(rows);
    }

    private static Map<LocalDate, Long> toMap(Result<Record2<LocalDate, Long>> rows) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }
}
