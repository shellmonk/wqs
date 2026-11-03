package wqs.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import wqs.domain.AggregationLevel;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;
import wqs.gen.TxTypes;
import wqs.read.HighLevelEntry;
import wqs.read.ProjectionResponse;
import wqs.read.ReadPropertyBase;
import wqs.read.SessionView;
import wqs.read.cc.CcProjectionService;
import wqs.read.web.WebProjectionService;

/**
 * P-7 (spec 7.4, 8.1) on a fixed dataset: every eInstant transaction carries a batchId, and the eInstant
 * view -- levels 1 and 2 filtered to the eInstant product types -- has no unsessioned entries.
 *
 * <p>The dataset offers the write path every way to break it: eInstant sessions (one starting
 * with a non-eInstant transaction), eInstant transactions WITHOUT a batchId from three sources (rejected at
 * ingest, plan D7), an unsessioned eInstant cancellation (exempt: it never gets a page row), and
 * unsessioned non-eInstant peers on the same instants, which the filter must leave out.
 */
@SpringBootTest
class EInstantInvariantTest extends ReadPropertyBase {

    private static final long USER = 1L;
    private static final Instant FROM = Instant.parse("2034-02-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2034-03-01T00:00:00Z");

    @Autowired EInstantProperties eInstant;
    @Autowired WebProjectionService web;
    @Autowired CcProjectionService cc;

    @BeforeEach
    void seed() {
        reset();
        List<ProjectionTransaction> d = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            String at = "2034-02-%02dT10:00:00Z".formatted(10 + s);
            String later = "2034-02-%02dT10:05:00Z".formatted(10 + s);
            String batch = "e" + s;
            d.add(transaction(SourceKind.TRANSACTION, batch + "-a", at, "instants", batch, TxTypes.PLAY));
            d.add(transaction(SourceKind.TRANSACTION, batch + "-b", later, "instants", batch, TxTypes.WIN));
            // unsessioned eInstant projected transactions: each must be rejected, never projected
            d.add(transaction(SourceKind.TRANSACTION, batch + "-loose", at, "instants", null, TxTypes.PLAY));
            d.add(transaction(SourceKind.CLAIMABLE_PRIZE, batch + "-loose-prize", at, "instants", null, TxTypes.WIN));
            d.add(transaction(SourceKind.PAYMENT, batch + "-loose-payment", at, "instants", null, TxTypes.NEUTRAL));
            // unsessioned non-eInstant peers on the same instant
            d.add(transaction(SourceKind.TRANSACTION, batch + "-keno", at, "keno", null, TxTypes.PLAY));
            d.add(transaction(SourceKind.PAYMENT, batch + "-pay", at, null, null, TxTypes.NEUTRAL));
        }
        // a keno-first session that later carries eInstant projected transactions: labelled keno (D6), still sessioned
        d.add(transaction(SourceKind.TRANSACTION, "k-a", "2034-02-20T09:00:00Z", "keno", "k", TxTypes.PLAY));
        d.add(transaction(SourceKind.TRANSACTION, "k-b", "2034-02-20T09:01:00Z", "instants", "k", TxTypes.PLAY));
        // an unsessioned eInstant cancellation: exempt at ingest, and no page row
        d.add(new ProjectionTransaction(USER, SourceKind.TRANSACTION, "e0-a-x", Instant.parse("2034-02-21T00:00:00Z"),
                "P-instants", "instants", null, null, TxTypes.CANCEL, new BigDecimal("-1"), "EUR", null, "e0-a", null));
        applyBatches(d);
    }

    /** Guards the dataset: the loose eInstant transactions were offered and refused, the rest projected. */
    @Test
    void dataset_offers_unsessioned_einstant_transactions_and_projects_the_rest() {
        assertThat(eInstant.productTypes()).contains("instants");
        assertThat(db().count("\"wqPrjCcTransaction\" where \"sourceId\" like '%-loose%'")).isZero();
        assertThat(db().count("\"wqPrjCcTransaction\" where \"productType\" = 'instants' and \"batchId\" is not null"))
                .isEqualTo(11);
        assertThat(db().count("\"wqPrjCcTransaction\" where \"sourceId\" = 'e0-a-x'")).isOne();
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();
    }

    @Test
    void every_einstant_transaction_has_a_batch() {
        assertThat(new ProjectionInvariants(jdbc()).eInstantDrift(eInstant.productTypes())).isEmpty();
    }

    /** The oracle above can fail: a stored unsessioned eInstant transaction and page row are drift. */
    @Test
    void einstant_drift_reports_an_unsessioned_transaction() {
        db().execute("""
                update "wqPrjTransaction" set "batchId" = null, "subBatchId" = null where "sourceId" = 'e1-a'""");
        db().execute("""
                insert into "wqPrjTimeline" ("userId", "occurredAt", "kind", "refId", "productType", "currency",
                        "onWeb")
                values (1, '2034-02-11T10:00:00Z', 1, 'planted', 'instants', 'EUR', true)""");
        assertThat(new ProjectionInvariants(jdbc()).eInstantDrift(eInstant.productTypes()))
                .anySatisfy(l -> assertThat(l).contains("unsessioned eInstant transaction").contains("e1-a"))
                .anySatisfy(l -> assertThat(l).contains("unsessioned eInstant page row").contains("planted"))
                .hasSize(2);
    }

    @Test
    void einstant_filter_has_no_unsessioned_entries_at_l1() {
        for (ViewKind v : ViewKind.values()) {
            for (AggregationLevel level : List.of(AggregationLevel.L1, AggregationLevel.L2)) {
                for (boolean excl : new boolean[] {false, true}) {
                    List<HighLevelEntry> all = new ArrayList<>();
                    long pages = 1;
                    for (long p = 1; p <= pages; p++) {
                        ProjectionResponse<HighLevelEntry> r = read(v, level, excl, p);
                        pages = r.pagesAvailable();
                        all.addAll(r.data());
                    }
                    String as = v + " " + level + " excl=" + excl;
                    assertThat(all).as(as).hasSize(5).allSatisfy(e -> assertThat(e).isInstanceOf(SessionView.class));
                    assertThat(all).as(as).extracting(e -> ((SessionView) e).productType()).containsOnly("instants");
                }
            }
        }
    }

    private ProjectionResponse<HighLevelEntry> read(ViewKind v, AggregationLevel level, boolean excl, long page) {
        Set<String> types = Set.copyOf(eInstant.productTypes());
        return v == ViewKind.WEB
                ? web.highLevel(USER, level, FROM, TO, excl, types, Set.of(), page, 2)
                : cc.highLevel(USER, level, FROM, TO, excl, types, Set.of(), page, 2);
    }

    private static ProjectionTransaction transaction(SourceKind source, String id, String at, String productType, String batchId,
                                       String type) {
        return new ProjectionTransaction(USER, source, id, Instant.parse(at), productType == null ? null : "P-" + productType,
                productType, batchId, batchId == null ? null : batchId + "-p", type, new BigDecimal("2"), "EUR", null,
                null, null);
    }
}
