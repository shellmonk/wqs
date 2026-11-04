package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.domain.SourceKind.TRANSACTION;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import wqs.domain.ProjectionTransaction;
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

    private static final YearMonth JAN = YearMonth.of(2034, 1);
    private static final YearMonth FEB = YearMonth.of(2034, 2);

    @Test
    void counter_reader_matches_live_counts_per_month() {
        project(
                transaction(TRANSACTION, "a", "2034-01-10T10:00:00Z", "P11", null, null),
                // No product type: its row has productType '' (none), so the month total equalling
                // the live count proves an unfiltered read sums every product type, none included.
                transaction(TRANSACTION, "b", "2034-01-20T11:00:00Z", null, null, null),
                transaction(TRANSACTION, "c", "2034-02-03T10:00:00Z", "P11", null, null),
                transaction(TRANSACTION, "x", "2034-02-03T12:00:00Z", "P11", "a", null));
        Level0Filter all = new Level0Filter(USER, false);

        var months = counters.monthCounts(readerJdbc, ReadPolicy.WEB, USER, TX_UNIT, NONE, NO_TYPES, false, JAN, FEB);
        assertThat(months).containsEntry(JAN,
                web.count(readerJdbc, all, Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-02-01T00:00:00Z")));
        assertThat(months).containsEntry(FEB,
                web.count(readerJdbc, all, Instant.parse("2034-02-01T00:00:00Z"), Instant.parse("2034-03-01T00:00:00Z")));
        assertThat(months).containsOnlyKeys(JAN, FEB).containsEntry(JAN, 2L);

        // excl column: "a" is cancelled
        assertThat(counters.monthCounts(readerJdbc, ReadPolicy.WEB, USER, TX_UNIT, NONE, NO_TYPES, true, JAN, JAN))
                .containsOnly(java.util.Map.entry(JAN, 1L));
        assertThat(counters.monthCounts(readerJdbc, ReadPolicy.WEB, USER, TX_UNIT, NONE, NO_TYPES, false,
                YearMonth.of(2034, 3), YearMonth.of(2034, 5))).isEmpty();
    }

    @Test
    void counter_reader_applies_the_policy_sources_owner_type_and_zero_amounts() {
        project(
                transaction(TRANSACTION, "a", "2034-01-10T10:00:00Z", "P11", null, null),
                transaction(wqs.domain.SourceKind.PAYMENT, "p", "2034-01-10T12:00:00Z", "P11", null, null),
                new ProjectionTransaction((short) 2, USER, TRANSACTION, "n", Instant.parse("2034-01-11T10:00:00Z"),
                        "P11", "keno", null, null, TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null),
                new ProjectionTransaction(USER, TRANSACTION, "z", Instant.parse("2034-01-12T10:00:00Z"),
                        "P11", "keno", null, null, TxTypes.PLAY, new BigDecimal("0.0000"), "EUR", null, null, null));
        Level0Filter all = new Level0Filter(USER, false);
        Instant lo = Instant.parse("2034-01-01T00:00:00Z");
        Instant hi = Instant.parse("2034-02-01T00:00:00Z");
        long webLive = web.count(readerJdbc, all, lo, hi);
        long ccLive = cc.count(readerJdbc, all, lo, hi);
        assertThat(counters.monthCounts(readerJdbc, ReadPolicy.WEB, USER, TX_UNIT, NONE, NO_TYPES, false, JAN, JAN))
                .containsOnly(java.util.Map.entry(JAN, webLive));
        assertThat(counters.monthCounts(readerJdbc, ReadPolicy.CC, USER, TX_UNIT, NONE, NO_TYPES, false, JAN, JAN))
                .containsOnly(java.util.Map.entry(JAN, ccLive));
        assertThat(webLive).isEqualTo(1);
        assertThat(ccLive).isEqualTo(2);
        // A policy that shows zero amounts, and one for the other owner type.
        ReadPolicy withZeros = new ReadPolicy(ReadPolicy.WEB.sources(), (short) 1, true);
        assertThat(counters.monthCounts(readerJdbc, withZeros, USER, TX_UNIT, NONE, NO_TYPES, false, JAN, JAN))
                .containsOnly(java.util.Map.entry(JAN, 2L));
        ReadPolicy owner2 = new ReadPolicy(ReadPolicy.WEB.sources(), (short) 2, false);
        assertThat(counters.monthCounts(readerJdbc, owner2, USER, TX_UNIT, NONE, NO_TYPES, false, JAN, JAN))
                .containsOnly(java.util.Map.entry(JAN, 1L));
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
        YearMonth mar = YearMonth.of(2034, 3);
        // A run of months summed, and the single month: the same totals.
        java.util.function.BiFunction<java.util.Set<String>, java.util.Set<String>, Long> run =
                (p, t) -> counters.monthCounts(readerJdbc, ReadPolicy.WEB, USER, TX_UNIT, p, t, false,
                                YearMonth.of(2034, 1), YearMonth.of(2034, 6))
                        .values().stream().mapToLong(Long::longValue).sum();
        java.util.function.BiFunction<java.util.Set<String>, java.util.Set<String>, Long> single =
                (p, t) -> counters.monthCounts(readerJdbc, ReadPolicy.WEB, USER, TX_UNIT, p, t, false, mar, mar)
                        .getOrDefault(mar, 0L);

        for (var f : java.util.List.of(run, single)) {
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
