package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.domain.SourceKind.TRANSACTION;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import wqs.domain.ProjectionTransaction;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;
import wqs.read.web.WebLevel0Query;

class CounterReaderTest extends Level0TestSupport {

    private static final java.util.Set<String> NONE = java.util.Set.of();
    private static final java.util.Set<String> NO_TYPES = java.util.Set.of();
    private static final wqs.domain.PageUnit TX_UNIT = wqs.domain.PageUnit.TRANSACTION;

    @Autowired CounterReader counters;
    @Autowired WebLevel0Query web;
    @Autowired wqs.read.cc.CcLevel0Query cc;

    @BeforeEach
    void clean() {
        reset();
    }

    @Test
    void counter_reader_matches_live_counts_per_day() {
        project(
                transaction(TRANSACTION, "a", "2034-01-10T10:00:00Z", "P11", null, null),
                // No product type: only the ALL key counts it, so the day total equalling the
                // live count proves the reader reads the ALL sentinel (''), not a product key.
                transaction(TRANSACTION, "b", "2034-01-10T11:00:00Z", null, null, null),
                transaction(TRANSACTION, "c", "2034-02-03T10:00:00Z", "P11", null, null),
                transaction(TRANSACTION, "x", "2034-02-03T12:00:00Z", "P11", "a", null));
        Level0Filter all = new Level0Filter(USER, false);

        var days = counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, NONE, NO_TYPES, false,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-28"));
        assertThat(days).containsEntry(LocalDate.parse("2034-01-10"),
                web.count(readerJdbc, all, Instant.parse("2034-01-10T00:00:00Z"), Instant.parse("2034-01-11T00:00:00Z")));
        assertThat(days).containsEntry(LocalDate.parse("2034-02-03"),
                web.count(readerJdbc, all, Instant.parse("2034-02-03T00:00:00Z"), Instant.parse("2034-02-04T00:00:00Z")));
        assertThat(days).containsOnlyKeys(LocalDate.parse("2034-01-10"), LocalDate.parse("2034-02-03"));

        // excl column: "a" is cancelled
        assertThat(counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, NONE, NO_TYPES, true,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-01-31")))
                .containsEntry(LocalDate.parse("2034-01-10"), 1L);

        assertThat(counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, NONE, NO_TYPES, false,
                LocalDate.parse("2034-01-09"), LocalDate.parse("2034-01-12")))
                .containsOnly(java.util.Map.entry(LocalDate.parse("2034-01-10"), 2L));
        assertThat(counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, NONE, NO_TYPES, false,
                LocalDate.parse("2034-01-11"), LocalDate.parse("2034-01-12"))).isEmpty();
    }

    @Test
    void counter_reader_separates_views() {
        project(
                transaction(TRANSACTION, "a", "2034-01-10T10:00:00Z", "P11", null, null),
                transaction(wqs.domain.SourceKind.PAYMENT, "p", "2034-01-10T12:00:00Z", "P11", null, null));
        Level0Filter all = new Level0Filter(USER, false);
        LocalDate day = LocalDate.parse("2034-01-10");
        Instant lo = Instant.parse("2034-01-10T00:00:00Z");
        Instant hi = Instant.parse("2034-01-11T00:00:00Z");
        long webLive = web.count(readerJdbc, all, lo, hi);
        long ccLive = cc.count(readerJdbc, all, lo, hi);
        assertThat(counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, NONE, NO_TYPES, false, day, day)).containsEntry(day, webLive);
        assertThat(counters.dayCounts(readerJdbc, USER, ViewKind.CC, TX_UNIT, NONE, NO_TYPES, false, day, day)).containsEntry(day, ccLive);
        assertThat(webLive).isNotEqualTo(ccLive);
    }

    @Test
    void reader_sums_cross_product_of_lists() {
        // product type x type: (a,PLAY) x2, (a,WIN) x1, (b,PLAY) x4, (b,WIN) x8, (none,PLAY) x16
        java.util.List<ProjectionTransaction> transactions = new java.util.ArrayList<>();
        int n = 0;
        for (Object[] g : new Object[][] {
                {"a", TxTypes.PLAY, 2}, {"a", TxTypes.WIN, 1}, {"b", TxTypes.PLAY, 4},
                {"b", TxTypes.WIN, 8}, {null, TxTypes.PLAY, 16}}) {
            for (int k = 0; k < (int) g[2]; k++) {
                transactions.add(new ProjectionTransaction(USER, TRANSACTION, "i" + n++,
                        Instant.parse("2034-03-10T10:00:00Z"), "P", (String) g[0], null, null,
                        (String) g[1], new BigDecimal("1.0000"), "EUR", null, null, null));
            }
        }
        project(transactions.toArray(ProjectionTransaction[]::new));
        LocalDate mar = LocalDate.parse("2034-03-01");
        LocalDate day = LocalDate.parse("2034-03-10");
        LocalDate marEnd = LocalDate.parse("2034-03-31");
        // A run of days summed over the month, and the single day: the same totals.
        java.util.function.BiFunction<java.util.Set<String>, java.util.Set<String>, Long> month =
                (p, t) -> counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, p, t, false, mar, marEnd)
                        .values().stream().mapToLong(Long::longValue).sum();
        java.util.function.BiFunction<java.util.Set<String>, java.util.Set<String>, Long> daily =
                (p, t) -> counters.dayCounts(readerJdbc, USER, ViewKind.WEB, TX_UNIT, p, t, false, day, day)
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
