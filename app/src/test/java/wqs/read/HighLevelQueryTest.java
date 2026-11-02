package wqs.read;

import static org.assertj.core.api.Assertions.assertThat;
import static wqs.domain.SourceKind.CLAIMABLE_PRIZE;
import static wqs.domain.SourceKind.PAYMENT;
import static wqs.domain.SourceKind.TRANSACTION;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.read.cc.CcHighLevelQuery;
import wqs.read.web.WebHighLevelQuery;

/** The per-view page-index queries (spec 5.6, 7.3): filters, half-open bounds, total order. */
class HighLevelQueryTest extends Level0TestSupport {

    @Autowired WebHighLevelQuery web;
    @Autowired CcHighLevelQuery cc;

    private static final Instant FEB = t("2034-02-01T00:00:00Z");
    private static final Instant MAR = t("2034-03-01T00:00:00Z");

    @BeforeEach
    void clean() {
        reset();
    }

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    private static ProjectionItem hl(SourceKind source, String id, String at, String productType,
                                     String batchId, String cancels) {
        return new ProjectionItem(USER, source, id, t(at), productType == null ? null : "P-" + productType,
                productType, batchId, batchId == null ? null : batchId + "-p0",
                cancels == null ? TxTypes.PLAY : TxTypes.CANCEL, new BigDecimal(cancels == null ? "1.0000" : "-1.0000"),
                "EUR", null, cancels, null);
    }

    @Test
    void count_respects_half_open_bounds_products_and_visibility() {
        project(
                hl(TRANSACTION, "a", "2034-02-01T00:00:00Z", "keno", null, null),     // on the lower bound
                hl(TRANSACTION, "b", "2034-03-01T00:00:00Z", "keno", null, null),     // on the upper bound: out
                hl(TRANSACTION, "s1", "2034-02-10T10:00:00Z", "lottery", "b1", null), // session b1, 2 items
                hl(TRANSACTION, "s2", "2034-02-11T10:00:00Z", "keno", "b1", null),
                hl(TRANSACTION, "c", "2034-02-12T10:00:00Z", "keno", null, null),
                hl(TRANSACTION, "c-x", "2034-02-12T11:00:00Z", "keno", null, "c"),    // hides c; no row itself
                hl(TRANSACTION, "d1", "2034-02-15T10:00:00Z", null, "b2", null),      // session b2, fully cancelled
                hl(TRANSACTION, "d1-x", "2034-02-15T11:00:00Z", null, null, "d1"),
                hl(PAYMENT, "p", "2034-02-20T10:00:00Z", null, null, null));          // /cc only

        assertThat(web.count(readerDsl, new HighLevelFilter(USER, false), FEB, MAR)).isEqualTo(4);
        assertThat(cc.count(readerDsl, new HighLevelFilter(USER, false), FEB, MAR)).isEqualTo(5);
        // c and b2 are hidden: "visibleExclCancelled" is false for them.
        assertThat(web.count(readerDsl, new HighLevelFilter(USER, true), FEB, MAR)).isEqualTo(2);
        assertThat(cc.count(readerDsl, new HighLevelFilter(USER, true), FEB, MAR)).isEqualTo(3);
        // b1 is labelled by its FIRST item's product ("lottery"), not "keno".
        assertThat(web.count(readerDsl, new HighLevelFilter(USER, Set.of("keno"), false), FEB, MAR)).isEqualTo(2);
        assertThat(web.count(readerDsl, new HighLevelFilter(USER, Set.of("lottery", "absent"), false), FEB, MAR))
                .isEqualTo(1);
        assertThat(web.count(readerDsl, new HighLevelFilter(USER, Set.of("absent"), false), FEB, MAR)).isZero();
        assertThat(web.count(readerDsl, new HighLevelFilter(USER + 1, false), FEB, MAR)).isZero();
        assertThat(web.count(readerDsl, new HighLevelFilter(USER, false), FEB, t("2034-02-01T00:00:01Z")))
                .isEqualTo(1);
    }

    /**
     * Total order ("occurredAt" desc, "kind" desc, "refId" desc) with every key on one instant:
     * kinds 4 (payment, /cc only), 3 (prize), 1 (transaction) and 0 (session), two refIds per
     * kind where possible. The plan is forced to seq-scan + sort, so index order cannot mask a
     * missing or flipped tie-break.
     */
    @Test
    void keys_order_ties_by_kind_then_refId_descending() {
        String at = "2034-02-10T10:00:00Z";
        project(
                hl(TRANSACTION, "sa", at, "keno", "b1", null),
                hl(TRANSACTION, "sb", at, "keno", "b2", null),
                hl(TRANSACTION, "t1", at, "keno", null, null),
                hl(TRANSACTION, "t2", at, "keno", null, null),
                hl(CLAIMABLE_PRIZE, "p1", at, "keno", null, null),
                hl(PAYMENT, "y1", at, null, null, null),
                hl(PAYMENT, "y2", at, null, null, null),
                hl(TRANSACTION, "older", "2034-02-09T10:00:00Z", "keno", null, null));
        Instant i = t(at);
        HighLevelFilter f = new HighLevelFilter(USER, false);

        List<HighLevelKey> all = seqScan(cc, f, 0, 20);
        assertThat(all).containsExactly(
                new HighLevelKey(i, (short) 4, "y2"),
                new HighLevelKey(i, (short) 4, "y1"),
                new HighLevelKey(i, (short) 3, "p1"),
                new HighLevelKey(i, (short) 1, "t2"),
                new HighLevelKey(i, (short) 1, "t1"),
                new HighLevelKey(i, (short) 0, "b2"),
                new HighLevelKey(i, (short) 0, "b1"),
                new HighLevelKey(t("2034-02-09T10:00:00Z"), (short) 1, "older"));
        assertThat(seqScan(cc, f, 2, 3)).isEqualTo(all.subList(2, 5));
        // /web holds no payments; same order otherwise.
        assertThat(seqScan(web, f, 0, 20)).isEqualTo(all.subList(2, 8));
        // The index-ordered plan agrees.
        assertThat(cc.keys(readerDsl, f, FEB, MAR, 0, 20)).isEqualTo(all);
    }

    private List<HighLevelKey> seqScan(HighLevelQuery q, HighLevelFilter f, long offset, int limit) {
        return readerDsl.transactionResult(cfg -> {
            var tx = DSL.using(cfg);
            tx.execute("set local enable_indexscan = off");
            tx.execute("set local enable_indexonlyscan = off");
            tx.execute("set local enable_bitmapscan = off");
            return q.keys(tx, f, FEB, MAR, offset, limit);
        });
    }
}
