package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.domain.SourceKind.TRANSACTION;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.domain.ViewKind;
import wqs.read.web.WebLevel0Query;

class CounterReaderTest extends Level0TestSupport {

    @Autowired CounterReader counters;
    @Autowired WebLevel0Query web;

    @BeforeEach
    void clean() {
        reset();
    }

    @Test
    void counter_reader_matches_counters_for_months_and_days() {
        project(
                item(TRANSACTION, "a", "2034-01-10T10:00:00Z", 11, null, null),
                item(TRANSACTION, "b", "2034-01-10T11:00:00Z", 13, null, null),
                item(TRANSACTION, "c", "2034-02-03T10:00:00Z", 11, null, null),
                item(TRANSACTION, "x", "2034-02-03T12:00:00Z", 11, "a", null));
        Level0Filter all = new Level0Filter(USER, null, false);
        Level0Filter excl = new Level0Filter(USER, null, true);
        Level0Filter p13 = new Level0Filter(USER, 13, false);

        var months = counters.months(readerDsl, ViewKind.WEB, all,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01"));
        assertThat(months).containsEntry(LocalDate.parse("2034-01-01"),
                web.count(readerDsl, all, Instant.parse("2034-01-01T00:00:00Z"), Instant.parse("2034-02-01T00:00:00Z")));
        assertThat(months).containsEntry(LocalDate.parse("2034-02-01"),
                web.count(readerDsl, all, Instant.parse("2034-02-01T00:00:00Z"), Instant.parse("2034-03-01T00:00:00Z")));
        assertThat(months).containsOnlyKeys(LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01"));

        // excl column: "a" is cancelled
        assertThat(counters.months(readerDsl, ViewKind.WEB, excl,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01")))
                .containsEntry(LocalDate.parse("2034-01-01"), 1L);

        // product key honoured
        assertThat(counters.months(readerDsl, ViewKind.WEB, p13,
                LocalDate.parse("2034-01-01"), LocalDate.parse("2034-02-01")))
                .containsOnly(java.util.Map.entry(LocalDate.parse("2034-01-01"), 1L));

        var days = counters.days(readerDsl, ViewKind.WEB, all,
                LocalDate.parse("2034-01-09"), LocalDate.parse("2034-01-12"));
        assertThat(days).containsOnly(java.util.Map.entry(LocalDate.parse("2034-01-10"), 2L));
        assertThat(counters.days(readerDsl, ViewKind.WEB, all,
                LocalDate.parse("2034-01-11"), LocalDate.parse("2034-01-12"))).isEmpty();
    }
}
