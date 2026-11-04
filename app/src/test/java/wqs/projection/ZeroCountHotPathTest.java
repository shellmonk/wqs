package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.Products;
import wqs.gen.TxTypes;
import wqs.read.StatementRecorder;

/**
 * Final-review I1: a new play and a new session -- almost every bet opens a play -- run the same
 * statements whatever the owner's history, and none of them reads "wqOwnerMonth" or
 * "wqPrjTransaction". A group's earlier zero-amount rows are counted incrementally
 * ("wqPrjPendingZero", by the group's own key), never searched for in the owner's months.
 */
class ZeroCountHotPathTest extends PostgresTestBase {

    private static final Instant AT = Instant.parse("2038-06-10T10:00:00Z");
    private static final long FRESH = 11L;
    private static final long HEAVY = 12L;

    @BeforeEach
    void setUp() {
        clearProjection();
        // HEAVY: three years of owner-month rows, zero-amount ones in every month, as a long-lived
        // player's history (the rows the old months probe walked for every new play and session).
        db().execute("""
                insert into "wqOwnerMonth" ("ownerType", "userId", "month", "source", "unit", "productType", "type",
                        "zeroAmount", "cnt", "cntExclCancelled")
                select 1, ?, cast(m as date), s, 0, 'pt' || p, 'TT_' || t, z, 1, 1
                from generate_series(timestamp '2035-01-01', timestamp '2038-05-01', interval '1 month') as m,
                     generate_series(1, 5) as s, generate_series(1, 4) as p, generate_series(1, 5) as t,
                     (values (true), (false)) as zz (z)
                where z = false or s = 1
                """, HEAVY);
        db().execute("analyze \"wqOwnerMonth\"");
    }

    @Test
    void a_new_play_and_session_run_the_same_statements_whatever_the_history() throws Exception {
        List<StatementRecorder.Executed> fresh = newPlayAndSession(FRESH).executed();
        StatementRecorder heavyRecorder = newPlayAndSession(HEAVY);
        List<StatementRecorder.Executed> heavy = heavyRecorder.executed();

        assertThat(heavy).as("statements were recorded").isNotEmpty();
        assertThat(heavy.stream().map(StatementRecorder.Executed::sql).toList())
                .as("the per-play statements do not depend on the owner's history")
                .isEqualTo(fresh.stream().map(StatementRecorder.Executed::sql).toList());
        for (StatementRecorder.Executed e : heavy) {
            String sql = e.sql();
            assertThat(sql).as("no history read").doesNotContain("\"wqOwnerMonth\"").doesNotContain("\"wqPrjTransaction\"");
            String plan = String.join("\n", heavyRecorder.rerun("explain (costs off)", e)).toLowerCase(Locale.ROOT);
            assertThat(plan).as(sql).doesNotContain("wqownermonth").doesNotContain("wqprjtransaction");
        }
    }

    /** A group's zero-amount rows that arrived before it are still counted, and nothing waits after. */
    @Test
    void zero_amount_rows_before_their_group_are_taken_by_it() {
        apply(zero("z-1", "b-z", "p-1"));
        apply(zero("z-2", "b-z", "p-2"));
        apply(zero("z-3", "b-z", null));
        assertThat(db().count("\"wqPrjPendingZero\"")).isEqualTo(3);

        apply(round("t-1", HEAVY, "b-z", "p-1"));
        assertThat(db().value("select \"zeroTxCount\" from \"wqPrjSession\" where \"batchId\" = 'b-z'", Integer.class))
                .isEqualTo(3);
        assertThat(db().value("select \"zeroTxCount\" from \"wqPrjPlay\" where \"subBatchId\" = 'p-1'", Integer.class))
                .isEqualTo(1);
        assertThat(db().column("select \"subBatchId\" from \"wqPrjPendingZero\"", String.class))
                .as("only p-2's row still waits, for its play").containsExactly("p-2");

        apply(zero("z-4", "b-z", "p-2")); // the session exists now; p-2 still does not
        apply(round("t-2", HEAVY, "b-z", "p-2"));
        assertThat(db().value("select \"zeroTxCount\" from \"wqPrjSession\" where \"batchId\" = 'b-z'", Integer.class))
                .isEqualTo(4);
        assertThat(db().value("select \"zeroTxCount\" from \"wqPrjPlay\" where \"subBatchId\" = 'p-2'", Integer.class))
                .isEqualTo(2);
        assertThat(db().count("\"wqPrjPendingZero\"")).as("nothing waits").isZero();
    }

    private StatementRecorder newPlayAndSession(long userId) {
        StatementRecorder recorder = new StatementRecorder(dataSource());
        inTx().executeWithoutResult(status ->
                updater(recorder.client()).applyTransaction(round("t-" + userId, userId, "b-" + userId, "p-1")));
        return recorder;
    }

    private void apply(ProjectionTransaction transaction) {
        inTx().executeWithoutResult(status -> updater(jdbc()).applyTransaction(transaction));
    }

    private static RollupUpdater updater(JdbcClient jdbc) {
        return new RollupUpdater(jdbc);
    }

    private static ProjectionTransaction round(String id, long userId, String batchId, String subBatchId) {
        return new ProjectionTransaction(userId, SourceKind.TRANSACTION, id, AT, Products.id(3), Products.type(3),
                batchId, subBatchId, TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null);
    }

    private static ProjectionTransaction zero(String id, String batchId, String subBatchId) {
        return new ProjectionTransaction(HEAVY, SourceKind.TRANSACTION, id, AT.minusSeconds(60), Products.id(3),
                Products.type(3), batchId, subBatchId, TxTypes.WIN, new BigDecimal("0.0000"), "EUR", null, null, null);
    }
}
