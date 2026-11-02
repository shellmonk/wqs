package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.db.generated.Tables.WQ_GROUP_LOCATOR;
import static wqs.db.generated.Tables.WQ_PLAY;
import static wqs.db.generated.Tables.WQ_SESSION;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.db.generated.tables.records.WqPlayRecord;
import wqs.db.generated.tables.records.WqSessionRecord;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;

class RollupUpdaterTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-05-10T10:00:00Z");

    @BeforeEach
    void setUp() {
        // April and May 2034: the month-boundary re-anchor test needs both partitions.
        new PartitionManager(dsl()).ensurePartitionsAhead(YearMonth.of(2034, 4), 1);
        clean();
    }

    @Test
    void a_first_transaction_creates_the_session_and_the_play() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));

        WqSessionRecord session = session("b1");
        assertThat(session.getTxCount()).isEqualTo(1);
        assertThat(session.getPlayCount()).isEqualTo(1);
        assertThat(session.getTotalPlays()).isEqualByComparingTo("5.0000");
        assertThat(session.getTotalWins()).isEqualByComparingTo("0.0000");
        assertThat(session.getNet()).isEqualByComparingTo("-5.0000");
    }

    @Test
    void later_transactions_accumulate_into_the_same_session() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(win("t2", "b1", "p1", "12.0000", T0.plusSeconds(30)));

        WqSessionRecord session = session("b1");
        assertThat(session.getTxCount()).isEqualTo(2);
        assertThat(session.getPlayCount()).isEqualTo(1);
        assertThat(session.getTotalPlays()).isEqualByComparingTo("5.0000");
        assertThat(session.getTotalWins()).isEqualByComparingTo("12.0000");
        assertThat(session.getNet()).isEqualByComparingTo("7.0000");
    }

    @Test
    void a_second_play_increments_play_count_only_once() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(wager("t2", "b1", "p2", "5.0000", T0.plusSeconds(60)));
        apply(win("t3", "b1", "p2", "1.0000", T0.plusSeconds(70)));

        assertThat(session("b1").getPlayCount()).isEqualTo(2);
        assertThat(session("b1").getTxCount()).isEqualTo(3);
    }

    @Test
    void play_level_metrics_mirror_the_session_shape() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(win("t2", "b1", "p1", "2.0000", T0.plusSeconds(10)));

        WqPlayRecord play = play("b1", "p1");
        assertThat(play.getTxCount()).isEqualTo(2);
        assertThat(play.getTotalPlays()).isEqualByComparingTo("5.0000");
        assertThat(play.getTotalWins()).isEqualByComparingTo("2.0000");
        assertThat(play.getNet()).isEqualByComparingTo("-3.0000");
    }

    @Test
    void an_earlier_item_arriving_second_re_anchors_the_session_and_the_play() {
        ProjectionItem later = wager("t1", "b1", "p1", "5.0000", T0.plusSeconds(100));
        ProjectionItem earlier = win("t2", "b1", "p1", "3.0000", T0);

        apply(later);
        apply(earlier);
        Map<String, List<Map<String, Object>>> outOfOrder = snapshot();

        assertThat(dsl().fetchCount(WQ_SESSION)).isEqualTo(1);
        assertThat(dsl().fetchCount(WQ_PLAY)).isEqualTo(1);
        WqSessionRecord session = session("b1");
        assertThat(session.getStartedAt()).isEqualTo(utc(T0));
        assertThat(session.getEndedAt()).isEqualTo(utc(T0.plusSeconds(100)));
        assertThat(session.getTxCount()).isEqualTo(2);
        assertThat(session.getPlayCount()).isEqualTo(1);
        assertThat(session.getNet()).isEqualByComparingTo("-2.0000");
        WqPlayRecord play = play("b1", "p1");
        assertThat(play.getStartedAt()).isEqualTo(utc(T0));
        assertThat(play.getEndedAt()).isEqualTo(utc(T0.plusSeconds(100)));
        assertThat(dsl().selectFrom(WQ_GROUP_LOCATOR).fetch(WQ_GROUP_LOCATOR.startedAt))
                .extracting(OffsetDateTime::toInstant)
                .containsExactly(T0, T0);

        clean();
        apply(earlier);
        apply(later);
        assertThat(outOfOrder).isEqualTo(snapshot());
    }

    @Test
    void re_anchoring_moves_the_row_across_a_month_boundary() {
        Instant mayFirst = Instant.parse("2034-05-01T00:00:10Z");
        Instant aprilLast = Instant.parse("2034-04-30T23:59:50Z");

        apply(wager("t1", "b1", "p1", "5.0000", mayFirst));
        apply(wager("t2", "b1", "p1", "4.0000", aprilLast));

        assertThat(dsl().fetchCount(DSL.table(DSL.name("wqSession_2034_04")))).isEqualTo(1);
        assertThat(dsl().fetchCount(DSL.table(DSL.name("wqSession_2034_05")))).isZero();
        assertThat(dsl().fetchCount(DSL.table(DSL.name("wqPlay_2034_04")))).isEqualTo(1);
        assertThat(dsl().fetchCount(DSL.table(DSL.name("wqPlay_2034_05")))).isZero();
        WqSessionRecord session = session("b1");
        assertThat(session.getStartedAt()).isEqualTo(utc(aprilLast));
        assertThat(session.getEndedAt()).isEqualTo(utc(mayFirst));
        assertThat(session.getTotalPlays()).isEqualByComparingTo("9.0000");
        assertThat(session.getTxCount()).isEqualTo(2);
        assertThat(play("b1", "p1").getStartedAt()).isEqualTo(utc(aprilLast));
    }

    @Test
    void a_play_can_re_anchor_while_its_session_does_not() {
        apply(wager("t1", "b1", "p1", "5.0000", T0));
        apply(wager("t2", "b1", "p2", "5.0000", T0.plusSeconds(50)));

        RollupResult result = apply(win("t3", "b1", "p2", "1.0000", T0.plusSeconds(30)));

        assertThat(result).isEqualTo(new RollupResult(false, false, null));
        assertThat(play("b1", "p2").getStartedAt()).isEqualTo(utc(T0.plusSeconds(30)));
        assertThat(play("b1", "p2").getEndedAt()).isEqualTo(utc(T0.plusSeconds(50)));
        assertThat(session("b1").getStartedAt()).isEqualTo(utc(T0));
        assertThat(session("b1").getPlayCount()).isEqualTo(2);
        assertThat(dsl().fetchCount(WQ_PLAY)).isEqualTo(2);
    }

    @Test
    void any_arrival_order_converges_to_identical_rollups() {
        List<ProjectionItem> chronological = List.of(
                wager("t1", "b1", "p1", "5.0000", T0, 12),
                win("t2", "b1", "p1", "7.5000", T0.plusSeconds(20)),
                wager("t3", "b1", "p2", "2.0000", T0.plusSeconds(40)),
                item("t4", "b1", "p2", "0.5000", T0.plusSeconds(50), (short) 7, 11),
                win("t5", "b1", "p2", "1.2500", T0.plusSeconds(80)));
        List<ProjectionItem> shuffled = List.of(
                chronological.get(3), chronological.get(1), chronological.get(4),
                chronological.get(0), chronological.get(2));

        chronological.forEach(this::apply);
        Map<String, List<Map<String, Object>>> inOrder = snapshot();

        clean();
        shuffled.forEach(this::apply);
        Map<String, List<Map<String, Object>>> outOfOrder = snapshot();

        assertThat(outOfOrder).isEqualTo(inOrder);
        WqSessionRecord session = session("b1");
        assertThat(session.getStartedAt()).isEqualTo(utc(T0));
        assertThat(session.getEndedAt()).isEqualTo(utc(T0.plusSeconds(80)));
        assertThat(session.getTxCount()).isEqualTo(5);
        assertThat(session.getPlayCount()).isEqualTo(2);
        assertThat(session.getProductId()).isEqualTo(11);
        assertThat(session.getTotalPlays()).isEqualByComparingTo("7.0000");
        assertThat(session.getTotalWins()).isEqualByComparingTo("8.7500");
        assertThat(session.getNet()).isEqualByComparingTo("1.7500");
    }

    @Test
    void the_result_reports_new_groups_and_re_anchoring() {
        assertThat(apply(wager("t1", "b1", "p1", "5.0000", T0.plusSeconds(100))))
                .isEqualTo(new RollupResult(true, true, null));
        assertThat(apply(win("t2", "b1", "p1", "1.0000", T0.plusSeconds(150))))
                .isEqualTo(new RollupResult(false, false, null));
        assertThat(apply(wager("t3", "b1", "p2", "1.0000", T0.plusSeconds(160))))
                .isEqualTo(new RollupResult(false, true, null));
        assertThat(apply(wager("t4", "b1", "p1", "1.0000", T0)))
                .isEqualTo(new RollupResult(false, false, T0.plusSeconds(100)));
        assertThat(apply(payment()))
                .isEqualTo(new RollupResult(false, false, null));
    }

    @Test
    void an_unsessioned_item_creates_no_rollup() {
        apply(payment());

        assertThat(dsl().fetchCount(WQ_SESSION)).isZero();
        assertThat(dsl().fetchCount(WQ_GROUP_LOCATOR)).isZero();
    }

    private RollupResult apply(ProjectionItem item) {
        return dsl().transactionResult(cfg -> new RollupUpdater(DSL.using(cfg)).applyItem(item));
    }

    private void clean() {
        dsl().deleteFrom(WQ_GROUP_LOCATOR).execute();
        dsl().deleteFrom(WQ_PLAY).execute();
        dsl().deleteFrom(WQ_SESSION).execute();
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of(
                "session", dsl().selectFrom(WQ_SESSION)
                        .orderBy(WQ_SESSION.userId, WQ_SESSION.batchId).fetchMaps(),
                "play", dsl().selectFrom(WQ_PLAY)
                        .orderBy(WQ_PLAY.userId, WQ_PLAY.batchId, WQ_PLAY.subBatchId).fetchMaps(),
                "locator", dsl().selectFrom(WQ_GROUP_LOCATOR)
                        .orderBy(WQ_GROUP_LOCATOR.userId, WQ_GROUP_LOCATOR.batchId,
                                 WQ_GROUP_LOCATOR.subBatchId)
                        .fetchMaps());
    }

    private static ProjectionItem payment() {
        return new ProjectionItem(
                1L, SourceKind.PAYMENT, "pay1", T0, null, null, null,
                (short) 9, new BigDecimal("50.0000"), "EUR", null, null, null);
    }

    private static ProjectionItem wager(String id, String batch, String play, String amount, Instant at) {
        return item(id, batch, play, amount, at, (short) 1, 11);
    }

    private static ProjectionItem wager(
            String id, String batch, String play, String amount, Instant at, int productId) {
        return item(id, batch, play, amount, at, (short) 1, productId);
    }

    private static ProjectionItem win(String id, String batch, String play, String amount, Instant at) {
        return item(id, batch, play, amount, at, (short) 2, 11);
    }

    private static ProjectionItem item(
            String id, String batch, String play, String amount, Instant at, short type,
            int productId) {
        return new ProjectionItem(
                1L, SourceKind.TRANSACTION, id, at, productId, batch, play,
                type, new BigDecimal(amount), "EUR", null, null, null);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private WqSessionRecord session(String batchId) {
        return dsl().selectFrom(WQ_SESSION).where(WQ_SESSION.batchId.eq(batchId)).fetchSingle();
    }

    private WqPlayRecord play(String batchId, String subBatchId) {
        return dsl().selectFrom(WQ_PLAY)
                .where(WQ_PLAY.batchId.eq(batchId), WQ_PLAY.subBatchId.eq(subBatchId))
                .fetchSingle();
    }
}
