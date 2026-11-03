package wqs.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import wqs.db.PostgresTestBase;

class QuartzSchemaTest extends PostgresTestBase {

    @Test
    void quartz_tables_live_in_their_own_schema() {
        List<String> tables = db().column("""
                       select table_name
                       from information_schema.tables
                       where table_schema = 'quartz'
                       order by table_name
                       """, String.class);

        assertThat(tables)
                .contains("qrtz_job_details", "qrtz_triggers", "qrtz_locks", "qrtz_scheduler_state");
    }

    @Test
    void no_quartz_tables_leaked_into_public() {
        Integer leaked = db().value("""
                          select count(*)::int
                          from information_schema.tables
                          where table_schema = 'public' and table_name ilike 'qrtz%'
                          """, Integer.class);

        assertThat(leaked).isZero();
    }
}
