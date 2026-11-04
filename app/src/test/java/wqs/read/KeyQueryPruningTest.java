package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;

/**
 * Plan D4: the key query is bounded by the days the page itself covers -- from the start of the
 * day holding the page's last row up to the end of the segment holding its first -- so a page
 * over a five-year range whose rows all sit in one month opens only that month's partition, not
 * every partition back to {@code from}. Checked on the plan of the key query the reader actually
 * ran, at both page units and in both views. (2037-2041: years no other test drops partitions of.)
 */
@SpringBootTest
class KeyQueryPruningTest extends ReadPropertyBase {

    // 2037-2041 on purpose: PartitionManagerTest drops every *_2031_* partition and
    // PartitionRolloverServiceTest drops 2032-06..09, and this test needs all 60 months of its range
    // provisioned and untouched by other classes sharing the container.
    private static final Instant FROM = Instant.parse("2037-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2042-01-01T00:00:00Z");
    private static final Pattern TRANSACTION_PARTITION = Pattern.compile("wqPrjTransaction_(\\d{4}_\\d{2})");
    private static final Pattern TIMELINE_PARTITION = Pattern.compile("wqPrjTimeline_(\\d{4}_\\d{2})");

    @Autowired @Qualifier("readerDataSource") javax.sql.DataSource readerDataSource;

    @BeforeEach
    void seed() {
        reset();
        // Every month of the five years has its partitions, as a long-running deployment would.
        partitionManager.ensurePartitionsAhead(YearMonth.of(2037, 1), 59);
        List<ProjectionTransaction> d = new ArrayList<>();
        for (String day : List.of("03", "10", "17", "24")) {
            d.add(transaction("newer-" + day, "2041-06-" + day + "T10:00:00Z"));   // page 1
            d.add(transaction("page-" + day, "2040-03-" + day + "T10:00:00Z"));    // page 2
            d.add(transaction("older-" + day, "2038-05-" + day + "T10:00:00Z"));   // page 3
        }
        applyBatches(d);
    }

    @Test
    void a_level_0_page_in_one_month_scans_only_that_months_partition() throws SQLException {
        for (ViewKind v : ViewKind.values()) {
            StatementRecorder recorder = new StatementRecorder(readerDataSource);
            Level0Query q = queryFor(v);
            Level0Query recorded = new Level0Query() {
                @Override
                public ViewKind view() {
                    return q.view();
                }

                @Override
                public ReadPolicy policy() {
                    return q.policy();
                }

                @Override
                public long count(JdbcClient tx, Level0Filter f, Instant lo, Instant hi) {
                    return q.count(tx, f, lo, hi);
                }

                @Override
                public List<TransactionKey> keys(JdbcClient tx, Level0Filter f, Instant lo, Instant hi, long o, int l) {
                    return q.keys(recorder.client(), f, lo, hi, o, l);
                }

                @Override
                public List<TransactionEntry> fetch(JdbcClient tx, long userId, List<TransactionKey> keys) {
                    return q.fetch(tx, userId, keys);
                }
            };
            Level0Filter f = new Level0Filter(1L, false);

            ProjectionPage page = reader.readLevel0(recorded, new Level0Request(f, FROM, TO, 2, 4));

            assertThat(page.transactions()).as(v.name()).isEqualTo(oracle.page(v, f, FROM, TO, 4, 4))
                    .extracting(TransactionEntry::sourceId).allMatch(id -> id.startsWith("page-"));
            assertThat(recorder.executed()).as(v + " one key query").hasSize(1);
            assertThat(partitions(recorder, TRANSACTION_PARTITION)).as(v + " key query partitions")
                    .containsExactly("2040_03");
        }
    }

    @Test
    void a_high_level_page_in_one_month_scans_only_that_months_partition() throws SQLException {
        for (ViewKind v : ViewKind.values()) {
            StatementRecorder recorder = new StatementRecorder(readerDataSource);
            HighLevelQuery q = highLevelQueryFor(v);
            HighLevelQuery recorded = new HighLevelQuery() {
                @Override
                public ViewKind view() {
                    return q.view();
                }

                @Override
                public ReadPolicy policy() {
                    return q.policy();
                }

                @Override
                public long count(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi) {
                    return q.count(tx, f, lo, hi);
                }

                @Override
                public List<HighLevelKey> keys(JdbcClient tx, HighLevelFilter f, Instant lo, Instant hi, long o, int l) {
                    return q.keys(recorder.client(), f, lo, hi, o, l);
                }
            };
            HighLevelFilter f = new HighLevelFilter(1L, false);
            ProjectionReader probe = new ProjectionReader(readerJdbc, readerTx, new CounterReader(),
                    new ReadProperties(null), new ReadMetrics(new SimpleMeterRegistry()), highLevelQueries());

            HighLevelPage<HighLevelKey> page = probe.readHighLevel(recorded,
                    new HighLevelRequest(v, AggregationLevel.L1, f, FROM, TO, 2, 4), (tx, keys) -> keys);

            assertThat(page.entries()).as(v.name()).isEqualTo(oracle.highLevelPage(v, f, FROM, TO, 4, 4))
                    .extracting(HighLevelKey::refId).allMatch(id -> id.startsWith("page-"));
            assertThat(recorder.executed()).as(v + " one key query").hasSize(1);
            assertThat(partitions(recorder, TIMELINE_PARTITION)).as(v + " key query partitions")
                    .containsExactly("2040_03");
        }
    }

    /** The months of every partition of the pattern's table named in the key query's plan. */
    private static Set<String> partitions(StatementRecorder recorder, Pattern partition) throws SQLException {
        String plan = String.join("\n", recorder.rerun("explain", recorder.executed().get(0)));
        Set<String> months = new TreeSet<>();
        Matcher m = partition.matcher(plan);
        while (m.find()) {
            months.add(m.group(1));
        }
        return months;
    }

    private static ProjectionTransaction transaction(String id, String at) {
        return new ProjectionTransaction(1L, SourceKind.TRANSACTION, id, Instant.parse(at), "P11", "keno", null, null,
                TxTypes.PLAY, new BigDecimal("1.0000"), "EUR", null, null, null);
    }
}
