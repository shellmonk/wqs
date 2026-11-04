package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;

/**
 * Plan D3, without a locator: a cancel's target lookup opens only the partitions of the months the
 * owner has transactions in ({@link OwnerMonths}), never every monthly partition. Checked on the
 * executed plan of the lookup statement itself: the scans of every other partition are never run.
 * (2036: a year no other test drops partitions of.)
 */
@SpringBootTest
class CancelLookupPruningTest extends PostgresTestBase {

    private static final Pattern EXECUTED_PARTITION =
            Pattern.compile("on \"?wqPrjTransaction_(\\d{4}_\\d{2})\"?(?![^\\n]*never executed)");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private OwnerMonths ownerMonths;
    @Autowired private PartitionManager partitionManager;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2036, 1), 11);
        clearProjection();
    }

    @Test
    void the_lookup_opens_only_the_owners_months() {
        writer.apply(new ProjectionBatch("s", List.of(
                transaction(1L, "t-feb", "2036-02-10T09:00:00Z"),
                transaction(1L, "t-sep", "2036-09-10T09:00:00Z"),
                transaction(2L, "other", "2036-05-10T09:00:00Z")), 1L));

        List<YearMonth> months = transactionTemplate.execute(status -> ownerMonths.of((short) 1, 1L));
        assertThat(months).containsExactly(YearMonth.of(2036, 2), YearMonth.of(2036, 9));

        String[] fromAt = months.stream().map(CancelLookupPruningTest::start).toArray(String[]::new);
        String[] toAt = months.stream().map(m -> start(m.plusMonths(1))).toArray(String[]::new);
        String plan = String.join("\n", db().column(
                "explain (analyze, costs off, timing off, summary off) " + CancellationApplier.LOCATE_ORIGINAL,
                String.class, fromAt, toAt, (short) SourceKind.TRANSACTION.code(), "t-feb", (short) 1, 1L));

        assertThat(executedPartitions(plan)).as(plan).containsExactly("2036_02", "2036_09");
    }

    private static Set<String> executedPartitions(String plan) {
        Set<String> months = new TreeSet<>();
        Matcher m = EXECUTED_PARTITION.matcher(plan);
        while (m.find()) {
            months.add(m.group(1));
        }
        return months;
    }

    private static String start(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    private static ProjectionTransaction transaction(long userId, String id, String at) {
        return new ProjectionTransaction(
                userId, SourceKind.TRANSACTION, id, Instant.parse(at), Products.id(3), Products.type(3),
                null, null, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }
}
