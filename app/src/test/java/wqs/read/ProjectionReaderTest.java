package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

@SpringBootTest
class ProjectionReaderTest extends ReadPropertyBase {

    private static final Level0Filter ALL = new Level0Filter(1L, null, false);

    @BeforeEach
    void clean() {
        reset();
    }

    /**
     * T5 ruling (spec 7.5 bounded cost): DAY counters are read per contiguous run of DAY segments,
     * so a whole middle month's daily rows are never fetched and discarded.
     */
    @Test
    void day_counters_are_read_per_run_not_across_whole_months() {
        applyBatches(List.of(
                item("j", "2034-01-20T10:00:00Z"),
                item("f1", "2034-02-01T10:00:00Z"),
                item("f15", "2034-02-15T10:00:00Z"),
                item("f28", "2034-02-28T10:00:00Z"),
                item("m", "2034-03-05T10:00:00Z")));
        RowCountingCounterReader counting = new RowCountingCounterReader();
        ProjectionReader probe = probeReader(counting);
        Instant from = Instant.parse("2034-01-10T00:00:00Z");
        Instant to = Instant.parse("2034-03-10T00:00:00Z");

        SegmentCounts sc = readerDsl.transactionResult(cfg ->
                probe.segmentCounts(DSL.using(cfg), webQuery, ALL, from, to));

        assertThat(sc.total()).isEqualTo(5).isEqualTo(oracle.count(ViewKind.WEB, ALL, from, to));
        // Feb's month row + the Jan 20 and Mar 5 day rows.
        assertThat(sc.counterRows()).isEqualTo(3);
        assertThat(counting.dayKeys).containsExactlyInAnyOrder(
                LocalDate.parse("2034-01-20"), LocalDate.parse("2034-03-05"));
        assertThat(counting.dayCalls).isEqualTo(2);
    }

    /** Builds a reader around a test counter reader; the rest is the context's. */
    private ProjectionReader probeReader(CounterReader counters) {
        return new ProjectionReader(readerDsl, counters);
    }

    /** Records every day row {@link CounterReader#days} returns, without a mocking framework. */
    static final class RowCountingCounterReader extends CounterReader {
        final List<LocalDate> dayKeys = new ArrayList<>();
        int dayCalls;

        @Override
        public Map<LocalDate, Long> days(
                DSLContext tx, ViewKind v, Level0Filter f, LocalDate firstPeriod, LocalDate lastPeriod) {
            Map<LocalDate, Long> rows = super.days(tx, v, f, firstPeriod, lastPeriod);
            dayKeys.addAll(rows.keySet());
            dayCalls++;
            return rows;
        }
    }

    static ProjectionItem item(String id, String at) {
        return new ProjectionItem(1L, SourceKind.TRANSACTION, id, Instant.parse(at), 11, null, null,
                (short) 1, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
