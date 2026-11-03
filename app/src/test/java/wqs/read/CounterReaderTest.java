package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.domain.SourceKind.TRANSACTION;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;
import wqs.read.web.WebLevel0Query;

class CounterReaderTest extends Level0TestSupport {

    private static final java.util.Set<String> NONE = java.util.Set.of();
    private static final java.util.Set<Short> NO_TYPES = java.util.Set.of();
    private static final wqs.domain.PageUnit ITEM = wqs.domain.PageUnit.ITEM;

    @Autowired CounterReader counters;
    @Autowired WebLevel0Query web;
    @Autowired wqs.read.cc.CcLevel0Query cc;

    @BeforeEach
    void clean() {
        reset();
    }

    @Test
    void counter_reader_matches_counters_for_months_and_days() {
        project(
                item(TRANSACTION, "a", "2034-01-10T10:00:00Z", "P11", null, null),
                // No product type: only the ALL key counts it, so the month total equalling the
                // live count proves the reader reads the ALL sentinel ('', 0), not a product key.
                item(TRANSACTION, "b", "2034-01-10T11:00:00Z", null, null, null),
                item(TRANSACTION, "c", "2034-02-03T10:00:00Z", "P11", null, null),
                item(TRANSACTION, "x", "2034-02-03T12:00:00Z", "P11", "a", null));
        Level0Filter all = new Level0Filter(USER, false);
        Level0Filter excl = new Level0Filter(USER, true);

        var months = counters.monthCounts(readerJdbc, USER, ViewKind.WEB, ITEM, NONE, NO_TYPES, false,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01"));
        assertThat(months).containsEntry(LocalDate.parse("2034-01-01"),
                web.count(readerJdbc, all, Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-02-01T00:00:00Z")));
        assertThat(months).containsEntry(LocalDate.parse("2034-02-01"),
                web.count(readerJdbc, all, Instant.parse("2034-02-01T00:00:00Z"), Instant.parse("2034-03-01T00:00:00Z")));
        assertThat(months).containsOnlyKeys(LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01"));

        // excl column: "a" is cancelled
        assertThat(counters.monthCounts(readerJdbc, USER, ViewKind.WEB, ITEM, NONE, NO_TYPES, true,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01")))
                .containsEntry(LocalDate.parse("2034-01-01"), 1L);

        var days = counters.dayCounts(readerJdbc, USER, ViewKind.WEB, ITEM, NONE, NO_TYPES, false,
                LocalDate.parse("2034-01-09"), LocalDate.parse("2034-01-12"));
        assertThat(days).containsOnly(java.util.Map.entry(LocalDate.parse("2034-01-10"), 2L));
        assertThat(counters.dayCounts(readerJdbc, USER, ViewKind.WEB, ITEM, NONE, NO_TYPES, false,
                LocalDate.parse("2034-01-11"), LocalDate.parse("2034-01-12"))).isEmpty();
    }

    @Test
    void counter_reader_separates_views() {
        project(
                item(TRANSACTION, "a", "2034-01-10T10:00:00Z", "P11", null, null),
                item(wqs.domain.SourceKind.PAYMENT, "p", "2034-01-11T10:00:00Z", "P11", null, null));
        Level0Filter all = new Level0Filter(USER, false);
        LocalDate jan = LocalDate.parse("2034-01-01");
        Instant lo = Instant.parse("2034-01-01T00:00:00Z");
        Instant hi = Instant.parse("2034-02-01T00:00:00Z");
        long webLive = web.count(readerJdbc, all, lo, hi);
        long ccLive = cc.count(readerJdbc, all, lo, hi);
        assertThat(counters.monthCounts(readerJdbc, USER, ViewKind.WEB, ITEM, NONE, NO_TYPES, false, jan, jan)).containsEntry(jan, webLive);
        assertThat(counters.monthCounts(readerJdbc, USER, ViewKind.CC, ITEM, NONE, NO_TYPES, false, jan, jan)).containsEntry(jan, ccLive);
        assertThat(webLive).isNotEqualTo(ccLive);
    }

    @Test
    void reader_sums_cross_product_of_lists() {
        // product type x type: (a,PLAY) x2, (a,WIN) x1, (b,PLAY) x4, (b,WIN) x8, (none,PLAY) x16
        java.util.List<ProjectionItem> items = new java.util.ArrayList<>();
        int n = 0;
        for (Object[] g : new Object[][] {
                {"a", TxTypes.PLAY, 2}, {"a", TxTypes.WIN, 1}, {"b", TxTypes.PLAY, 4},
                {"b", TxTypes.WIN, 8}, {null, TxTypes.PLAY, 16}}) {
            for (int k = 0; k < (int) g[2]; k++) {
                items.add(new ProjectionItem(USER, TRANSACTION, "i" + n++,
                        Instant.parse("2034-03-10T10:00:00Z"), "P", (String) g[0], null, null,
                        (short) g[1], new BigDecimal("1.0000"), "EUR", null, null, null));
            }
        }
        project(items.toArray(ProjectionItem[]::new));
        LocalDate mar = LocalDate.parse("2034-03-01");
        LocalDate day = LocalDate.parse("2034-03-10");
        java.util.function.BiFunction<java.util.Set<String>, java.util.Set<Short>, Long> month =
                (p, t) -> counters.monthCounts(readerJdbc, USER, ViewKind.WEB, ITEM, p, t, false, mar, mar)
                        .getOrDefault(mar, 0L);
        java.util.function.BiFunction<java.util.Set<String>, java.util.Set<Short>, Long> daily =
                (p, t) -> counters.dayCounts(readerJdbc, USER, ViewKind.WEB, ITEM, p, t, false, day, day)
                        .getOrDefault(day, 0L);

        for (var f : java.util.List.of(month, daily)) {
            assertThat(f.apply(NONE, NO_TYPES)).isEqualTo(31L);
            assertThat(f.apply(java.util.Set.of("a", "b"), NO_TYPES)).isEqualTo(15L);
            assertThat(f.apply(NONE, java.util.Set.of(TxTypes.WIN))).isEqualTo(9L);
            assertThat(f.apply(java.util.Set.of("a", "b"), java.util.Set.of(TxTypes.WIN))).isEqualTo(9L);
            assertThat(f.apply(java.util.Set.of("a"), java.util.Set.of(TxTypes.PLAY, TxTypes.WIN)))
                    .isEqualTo(3L);
            assertThat(f.apply(java.util.Set.of("b"), java.util.Set.of(TxTypes.PLAY))).isEqualTo(4L);
            assertThat(f.apply(java.util.Set.of("zzz"), NO_TYPES)).isZero();
        }
    }
}
