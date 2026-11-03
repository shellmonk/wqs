package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.db.PartitionManager;
import wqs.db.PostgresTestBase;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.source.DeltaRow;

/** The versioned upsert of polled rows (spec 4; phase 6 plan D6, D7, R1). */
@SpringBootTest
class VersionedApplyTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2034-11-04T11:00:00Z");
    private static final Instant V1 = T0.plusSeconds(60);
    private static final Instant V2 = T0.plusSeconds(120);
    private static final String DETAIL_V1 = "{\"status\": \"UNCLAIMED\"}";
    private static final String DETAIL_V2 = "{\"status\": \"CLAIMED\"}";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private ProjectionWriter writer;
    @Autowired private PartitionManager partitionManager;
    @Autowired private MeterRegistry meters;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DeltaWatermarkStore watermarks;

    @BeforeEach
    void setUp() {
        partitionManager.ensurePartitionsAhead(YearMonth.of(2034, 10), 1);
        clearProjection();
    }

    @Test
    void first_version_inserts_like_a_stream_item() {
        DeltaResult result = writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1), payment("pay1", V1)));

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(count("wqWebItem")).isEqualTo(1);    // the prize; payments are /cc only
        assertThat(count("wqCcItem")).isEqualTo(2);
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(2);
        assertThat(itemSentinel(1)).isEqualTo(1);       // web ITEM counter
        assertThat(itemSentinel(2)).isEqualTo(2);       // cc ITEM counter
        assertThat(versionAt(SourceKind.CLAIMABLE_PRIZE, "pr1")).isEqualTo(V1);
        assertThat(versionAt(SourceKind.PAYMENT, "pay1")).isEqualTo(V1);
        assertThat(detail("wqWebItem", "pr1")).isEqualTo(DETAIL_V1);
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    @Test
    void a_zero_amount_polled_row_is_projected() {
        DeltaRow limit = row(SourceKind.LIMIT, 1, "lim1", T0, null, BigDecimal.ZERO, "XXX", V1, null);
        assertThat(writer.applyDelta(batch(V1, limit)).inserted()).isEqualTo(1);
        assertThat(count("wqCcItem")).isEqualTo(1);
    }

    @Test
    void newer_version_updates_detail_only() {
        writer.applyDelta(batch(V1, prize("pr1", "b1", V1, DETAIL_V1), payment("pay1", V1)));
        ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());
        List<String> itemsBefore = itemsWithoutDetail();

        DeltaResult result = writer.applyDelta(batch(V2, prize("pr1", "b1", V2, DETAIL_V2)));

        assertThat(result.updated()).isEqualTo(1);
        ProjectionSnapshot after = ProjectionSnapshot.of(jdbc());
        assertThat(itemsWithoutDetail()).isEqualTo(itemsBefore);
        assertThat(after.groupLocator()).isEqualTo(before.groupLocator());
        assertThat(after.sessions()).isEqualTo(before.sessions());
        assertThat(after.plays()).isEqualTo(before.plays());
        assertThat(after.webHighLevel()).isEqualTo(before.webHighLevel());
        assertThat(after.ccHighLevel()).isEqualTo(before.ccHighLevel());
        assertThat(after.countDay()).isEqualTo(before.countDay());
        assertThat(after.countMonth()).isEqualTo(before.countMonth());
        assertThat(after.locator()).isEqualTo(before.locator());
        // Replaced in every view the source feeds.
        assertThat(detail("wqWebItem", "pr1")).isEqualTo(DETAIL_V2);
        assertThat(detail("wqCcItem", "pr1")).isEqualTo(DETAIL_V2);
        assertThat(versionAt(SourceKind.CLAIMABLE_PRIZE, "pr1")).isEqualTo(V2);
        assertThat(versionAt(SourceKind.PAYMENT, "pay1")).isEqualTo(V1);
    }

    @Test
    void equal_version_is_duplicate() {
        writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1)));
        ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());

        DeltaResult result = writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V2)));

        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(ProjectionSnapshot.of(jdbc())).isEqualTo(before);
    }

    @Test
    void older_version_is_stale_and_ignored() {
        writer.applyDelta(batch(V2, prize("pr1", null, V2, DETAIL_V2)));
        ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());
        double stale = meters.counter("wqs.delta.stale").count();
        double staleRows = meters.counter("wqs.delta.rows", "outcome", "stale").count();

        DeltaResult result = writer.applyDelta(batch(V2, prize("pr1", null, V1, DETAIL_V1)));

        assertThat(result.stale()).isEqualTo(1);
        assertThat(ProjectionSnapshot.of(jdbc())).isEqualTo(before);
        assertThat(versionAt(SourceKind.CLAIMABLE_PRIZE, "pr1")).isEqualTo(V2);
        assertThat(meters.counter("wqs.delta.stale").count()).isEqualTo(stale + 1);
        assertThat(meters.counter("wqs.delta.rows", "outcome", "stale").count()).isEqualTo(staleRows + 1);
    }

    @Test
    void newer_version_changing_amount_dead_letters() {
        writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1)));
        ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());

        DeltaRow changed = row(SourceKind.CLAIMABLE_PRIZE, 1, "pr1", T0, null,
                new BigDecimal("999.0000"), "EUR", V2, DETAIL_V2);
        DeltaResult result = writer.applyDelta(batch(V2, changed));

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(ProjectionSnapshot.of(jdbc())).isEqualTo(before);
        assertThat(versionAt(SourceKind.CLAIMABLE_PRIZE, "pr1")).isEqualTo(V1);
        assertThat(deadLetterErrors()).containsExactly(RejectedItemDeadLetters.IMMUTABLE_FIELD_CHANGED);
    }

    @Test
    void newer_version_changing_occurredAt_dead_letters() {
        writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1)));
        ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());

        DeltaRow moved = row(SourceKind.CLAIMABLE_PRIZE, 1, "pr1", T0.plusSeconds(1), null,
                new BigDecimal("150.0000"), "EUR", V2, DETAIL_V2);
        DeltaResult result = writer.applyDelta(batch(V2, moved));

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(ProjectionSnapshot.of(jdbc())).isEqualTo(before);
        assertThat(deadLetterErrors()).containsExactly(RejectedItemDeadLetters.IMMUTABLE_FIELD_CHANGED);
    }

    /** D6: every immutable field other than amount/occurredAt (tested above), one at a time. */
    @Test
    void newer_version_changing_any_other_immutable_field_dead_letters() {
        ProjectionItem base = prize("pr1", "b1", V1, DETAIL_V1).item();
        java.util.Map<String, ProjectionItem> changes = new java.util.LinkedHashMap<>();
        changes.put("currency", copy(base, base.userId(), base.productId(), base.productType(), base.batchId(),
                base.type(), "USD"));
        changes.put("type", copy(base, base.userId(), base.productId(), base.productType(), base.batchId(),
                TxTypes.PLAY, base.currency()));
        changes.put("productId", copy(base, base.userId(), "P99", base.productType(), base.batchId(),
                base.type(), base.currency()));
        changes.put("productType", copy(base, base.userId(), base.productId(), "instants", base.batchId(),
                base.type(), base.currency()));
        changes.put("batchId", copy(base, base.userId(), base.productId(), base.productType(), "b2",
                base.type(), base.currency()));
        changes.put("userId", copy(base, 2L, base.productId(), base.productType(), base.batchId(),
                base.type(), base.currency()));

        for (var change : changes.entrySet()) {
            clearProjection();
            writer.applyDelta(batch(V1, prize("pr1", "b1", V1, DETAIL_V1)));
            ProjectionSnapshot before = ProjectionSnapshot.of(jdbc());

            ProjectionItem item = change.getValue();
            DeltaResult result = writer.applyDelta(batch(V2,
                    new DeltaRow(item.source(), 1, item.userId(), V2, item)));

            assertThat(result.deadLettered()).as(change.getKey()).isEqualTo(1);
            assertThat(result.updated()).as(change.getKey()).isZero();
            assertThat(ProjectionSnapshot.of(jdbc())).as(change.getKey()).isEqualTo(before);
            assertThat(versionAt(SourceKind.CLAIMABLE_PRIZE, "pr1")).as(change.getKey()).isEqualTo(V1);
            assertThat(detail("wqCcItem", "pr1")).as(change.getKey()).isEqualTo(DETAIL_V1);
            assertThat(deadLetterErrors()).as(change.getKey())
                    .containsExactly(RejectedItemDeadLetters.IMMUTABLE_FIELD_CHANGED);
        }
    }

    private static ProjectionItem copy(ProjectionItem base, long userId, String productId, String productType,
            String batchId, short type, String currency) {
        return new ProjectionItem(userId, base.source(), base.sourceId(), base.occurredAt(), productId, productType,
                batchId, base.subBatchId(), type, base.amount(), currency, null, null, DETAIL_V2);
    }

    @Test
    void newer_version_with_an_equal_amount_at_another_scale_is_an_update() {
        writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1)));
        DeltaRow sameAmount = row(SourceKind.CLAIMABLE_PRIZE, 1, "pr1", T0, null,
                new BigDecimal("150.000"), "EUR", V2, DETAIL_V2);
        assertThat(writer.applyDelta(batch(V2, sameAmount)).updated()).isEqualTo(1);
    }

    @Test
    void non_player_writes_nothing() {
        double skipped = meters.counter("wqs.ingest.skipped", "reason", "non-player").count();
        DeltaRow retailer = row(SourceKind.PAYMENT, 2, "pay9", T0, null, new BigDecimal("5.0000"), "EUR", V1, null);

        DeltaResult result = writer.applyDelta(batch(null, retailer));

        assertThat(result.skipped()).isEqualTo(1);
        for (String table : List.of("wqItemLocator", "wqCcItem", "wqCcHighLevel", "wqCountDay", "wqDeadLetter")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(meters.counter("wqs.ingest.skipped", "reason", "non-player").count()).isEqualTo(skipped + 1);
    }

    @Test
    void watermark_commits_with_the_batch_and_rolls_back_with_it() {
        writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1)));
        assertThat(watermarks.read()).contains(V1);

        // A failure after the items (and the watermark) are written: the enclosing transaction
        // rolls back, so neither persists. A watermark written in its own transaction would survive.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writer.applyDelta(batch(V2, prize("pr2", null, V2, DETAIL_V2)));
            throw new IllegalStateException("simulated failure after the items");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(watermarks.read()).contains(V1);
        assertThat(count("wqItemLocator")).isEqualTo(1);
        assertThat(count("wqCcItem")).isEqualTo(1);
    }

    @Test
    void an_empty_page_leaves_the_watermark_unchanged_and_it_never_moves_backwards() {
        writer.applyDelta(batch(V2, prize("pr1", null, V2, DETAIL_V1)));
        writer.applyDelta(new DeltaBatch(List.of(), null));
        assertThat(watermarks.read()).contains(V2);
        writer.applyDelta(batch(V1, prize("pr1", null, V1, DETAIL_V1)));
        assertThat(watermarks.read()).contains(V2);
    }

    @Test
    void sessioned_prize_joins_its_session() {
        writer.apply(new ProjectionBatch("s", List.of(new ProjectionItem(
                1L, SourceKind.TRANSACTION, "t1", T0.minusSeconds(30), "P11", "lotto", "b1", null,
                TxTypes.PLAY, new BigDecimal("5.0000"), "EUR", null, null, null)), 1L));

        writer.applyDelta(batch(V1, prize("pr1", "b1", V1, DETAIL_V1)));

        assertThat(count("wqSession")).isEqualTo(1);
        assertThat(db().value("select \"txCount\" from \"wqSession\"", Integer.class)).isEqualTo(2);
        assertThat(count("wqWebHighLevel")).isEqualTo(1);
        assertThat(count("wqCcHighLevel")).isEqualTo(1);
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static DeltaBatch batch(Instant watermark, DeltaRow... rows) {
        return new DeltaBatch(List.of(rows), watermark);
    }

    private static DeltaRow prize(String id, String batchId, Instant version, String detail) {
        return row(SourceKind.CLAIMABLE_PRIZE, 1, id, T0, batchId, new BigDecimal("150.0000"), "EUR", version, detail);
    }

    private static DeltaRow payment(String id, Instant version) {
        return row(SourceKind.PAYMENT, 1, id, T0, null, new BigDecimal("-25.5000"), "EUR", version, null);
    }

    private static DeltaRow row(SourceKind source, int ownerType, String id, Instant occurredAt, String batchId,
            BigDecimal amount, String currency, Instant version, String detail) {
        boolean prize = source == SourceKind.CLAIMABLE_PRIZE;
        return new DeltaRow(source, ownerType, 1L, version, new ProjectionItem(
                1L, source, id, occurredAt, prize ? "P11" : null, prize ? "lotto" : null, batchId, null,
                TxTypes.NEUTRAL, amount, currency, null, null, detail));
    }

    private int count(String table) {
        return db().value("select count(*)::int from \"" + table + "\"", Integer.class);
    }

    private int itemSentinel(int view) {
        return db().value("""
                select coalesce(sum("cntAll"),0)::int from "wqCountDay"
                where "viewKind" = ? and "pageUnit" = 0 and "productType" = '' and "typeId" = 0
                """, Integer.class, (short) view);
    }

    private Instant versionAt(SourceKind source, String sourceId) {
        java.time.OffsetDateTime at = db().value(
                "select \"versionAt\" from \"wqItemLocator\" where \"source\" = ? and \"sourceId\" = ?", java.time.OffsetDateTime.class,
                (short) source.code(), sourceId);
        return at == null ? null : at.toInstant();
    }

    private String detail(String table, String sourceId) {
        return db().value("select \"detail\"::text from \"" + table + "\" where \"sourceId\" = ?", String.class, sourceId);
    }

    private List<String> itemsWithoutDetail() {
        return db().lines("""
                select 'web', "userId", "itemId", "source", "sourceId", "occurredAt", "productId", "productType",
                       "batchId", "subBatchId", "type", "amount", "currency", "balanceAfter", "cancelled",
                       "cancelledBy", "cancels"
                from "wqWebItem"
                union all
                select 'cc', "userId", "itemId", "source", "sourceId", "occurredAt", "productId", "productType",
                       "batchId", "subBatchId", "type", "amount", "currency", "balanceAfter", "cancelled",
                       "cancelledBy", "cancels"
                from "wqCcItem"
                order by 1, 5""");
    }

    private List<String> deadLetterErrors() {
        return db().column("select \"error\" from \"wqDeadLetter\"", String.class);
    }
}
