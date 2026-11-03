package wqs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import wqs.db.PostgresTestBase;

class IngestStateMigrationTest extends PostgresTestBase {

    @Test
    void stream_offset_table_exists_with_a_single_row_per_stream() {
        List<String> pk = db().column("""
                       select a.attname
                       from pg_index i
                       join pg_class c on c.oid = i.indrelid
                       join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
                       where c.relname = 'wqStreamOffset' and i.indisprimary
                       """, String.class);

        assertThat(pk).containsExactly("stream");
    }

    @Test
    void dead_letter_table_retains_the_raw_payload() {
        List<String> columns = db().column("""
                       select column_name from information_schema.columns
                       where table_name = 'wqDeadLetter'
                       """, String.class);

        assertThat(columns).contains("payload", "error", "failedAt", "streamOffset");
    }
}
