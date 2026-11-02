package wqs.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** I1 / R100: on-demand provisioning, its per-month cache and its sanity window. */
class PartitionProvisionerTest extends PostgresTestBase {

    private static final Clock NOV_2026 = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

    private final List<YearMonth> calls = new ArrayList<>();
    private final PartitionManager counting = new PartitionManager(dsl()) {
        @Override
        public int ensurePartitionsAhead(YearMonth from, int monthsAhead) {
            calls.add(from);
            return super.ensurePartitionsAhead(from, monthsAhead);
        }
    };

    private PartitionProvisioner provisioner(int maxMonthsAhead) {
        return new PartitionProvisioner(
                counting, new DataSourceTransactionManager(dataSource()), NOV_2026, maxMonthsAhead);
    }

    @Test
    void provisions_each_month_once_and_then_serves_it_from_the_cache() {
        PartitionProvisioner provisioner = provisioner(12);
        YearMonth month = YearMonth.of(2019, 3); // no past floor

        provisioner.ensureMonths(Set.of(month));
        provisioner.ensureMonths(Set.of(month));
        provisioner.ensureMonths(Set.of(month, YearMonth.of(2019, 4)));

        assertThat(calls).containsExactly(month, YearMonth.of(2019, 4));
        assertThat(counting.runwayMonths(month)).isEqualTo(2);
    }

    @Test
    void forgetting_the_cache_re_checks_the_catalog() {
        PartitionProvisioner provisioner = provisioner(12);
        provisioner.ensureMonths(Set.of(YearMonth.of(2019, 5)));
        provisioner.forgetKnownMonths();
        provisioner.ensureMonths(Set.of(YearMonth.of(2019, 5)));

        assertThat(calls).hasSize(2);
    }

    @Test
    void a_month_more_than_twelve_ahead_is_rejected_as_poison_without_any_ddl() {
        PartitionProvisioner provisioner = provisioner(12);

        provisioner.ensureMonths(Set.of(YearMonth.of(2027, 11))); // now + 12: allowed
        assertThatThrownBy(() -> provisioner.ensureMonths(Set.of(YearMonth.of(2026, 1), YearMonth.of(2027, 12))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2027-12");

        assertThat(calls).containsExactly(YearMonth.of(2027, 11));
    }
}
