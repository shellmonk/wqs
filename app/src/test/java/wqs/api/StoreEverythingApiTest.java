package wqs.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.gen.TxTypes;
import wqs.projection.ProjectionInvariants;

/**
 * Store everything, show by read policy (plan D1, D6). Non-player owners and zero-amount stream
 * transactions are stored, yet every response of both endpoints -- totals, pages, sessions,
 * plays and nested transactions, at every level, with and without cancelled -- is byte-for-byte
 * the response for the player's visible rows alone.
 */
class StoreEverythingApiTest extends ApiTestBase {

    private static final long PLAYER = 1L;
    private static final short RETAILER = 2;

    /** The player's visible rows: a session with a play, a standalone transaction, a payment, a cancel. */
    private static List<ProjectionTransaction> visible() {
        return List.of(
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "s-a", "2034-02-10T10:00:00Z", "s1", "s1-p1", "12.5000", null),
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "s-b", "2034-02-10T10:05:00Z", "s1", null, "3.0000", null),
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "t1", "2034-02-11T10:00:00Z", null, null, "7.0000", null),
                row(ProjectionTransaction.PLAYER, SourceKind.PAYMENT, "p1", "2034-02-12T10:00:00Z", null, null, "20.0000", null),
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "c1", "2034-02-12T11:00:00Z", null, null, "-7.0000", "t1"));
    }

    /**
     * Rows that must never show: owner "2-1" (same numeric id as the player) -- standalone, in a
     * session of the SAME batch id, a payment, and a cancel naming the player's transaction -- and the
     * player's zero-amount rows, standalone, in the player's session and play, and a cancel of one.
     * Each lies on a day the player is active, inside the player's session window, and newer than a
     * visible row, so the counter-bounded walk cannot hide a missing filter.
     */
    private static List<ProjectionTransaction> hidden() {
        return List.of(
                row(RETAILER, SourceKind.TRANSACTION, "r-t", "2034-02-11T12:00:00Z", null, null, "9.0000", null),
                row(RETAILER, SourceKind.TRANSACTION, "r-s", "2034-02-10T10:02:00Z", "s1", "s1-p1", "4.0000", null),
                row(RETAILER, SourceKind.PAYMENT, "r-p", "2034-02-12T09:00:00Z", null, null, "50.0000", null),
                row(RETAILER, SourceKind.TRANSACTION, "r-c", "2034-02-12T12:00:00Z", null, null, "-1.0000", "s-b"),
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "z-u", "2034-02-11T11:00:00Z", null, null, "0.0000", null),
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "z-s", "2034-02-10T10:03:00Z", "s1", "s1-p1", "0.0000", null),
                row(ProjectionTransaction.PLAYER, SourceKind.TRANSACTION, "z-c", "2034-02-12T11:30:00Z", null, null, "0.0000", "z-u"));
    }

    @Test
    void non_players_and_zero_amounts_are_stored_and_never_change_a_response() throws Exception {
        reset();
        project(visible().toArray(ProjectionTransaction[]::new));
        Map<String, String> baseline = responses();
        assertThat(baseline.values()).as("the fixture is not empty").allMatch(body -> body.contains("\"data\":[{"));

        reset();
        List<ProjectionTransaction> all = new ArrayList<>(hidden());
        all.addAll(visible());
        project(all.toArray(ProjectionTransaction[]::new));

        assertThat(db().value("select count(*)::int from \"wqPrjTransaction\"", Integer.class))
                .as("everything is stored").isEqualTo(visible().size() + hidden().size());
        assertThat(db().value("select count(*)::int from \"wqPrjTransaction\" where \"ownerType\" = 2 and \"userId\" = 1",
                Integer.class)).isEqualTo(4);
        assertThat(db().value("select count(*)::int from \"wqPrjTransaction\" where \"zeroAmount\"", Integer.class))
                .isEqualTo(3);
        assertThat(db().value("select \"cancelled\" from \"wqPrjTransaction\" where \"sourceId\" = 'z-u'", Boolean.class))
                .as("a cancel of a zero-amount row resolves").isTrue();
        assertThat(db().value("select \"cancelled\" from \"wqPrjTransaction\" where \"sourceId\" = 's-b'", Boolean.class))
                .as("another owner type's cancel never resolves against the player's row").isFalse();
        assertThat(db().value("select count(*)::int from \"wqPrjPendingCancel\" where \"ownerType\" = 2", Integer.class))
                .as("it parks under its own owner").isEqualTo(1);
        assertThat(db().value("select count(*)::int from \"wqPrjCount\" where \"zeroAmount\"", Integer.class))
                .as("zero-amount rows are counted apart").isPositive();
        assertThat(db().value("""
                select "zeroTxCount" from "wqPrjSession" where "ownerType" = 1 and "batchId" = 's1'""", Integer.class))
                .isEqualTo(1);
        assertThat(new ProjectionInvariants(jdbc()).drift()).isEmpty();

        Map<String, String> withHidden = responses();
        for (Map.Entry<String, String> e : baseline.entrySet()) {
            assertThat(withHidden.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    /** Every response shape of both endpoints for the player, keyed by its request. */
    private Map<String, String> responses() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String endpoint : List.of("web", "cc")) {
            for (String level : List.of("0", "1", "2")) {
                for (String excl : List.of("false", "true")) {
                    for (String pageSize : List.of("2", "100")) {
                        MockHttpServletRequestBuilder req = endpoint.equals("web")
                                ? get("/web/wallet-data/rest/v1/projection").header(PRINCIPAL_HEADER, Long.toString(PLAYER))
                                : get("/cc/wallet-data/rest/v1/projection/{playerId}", PLAYER);
                        req.param("from", FROM).param("to", TO).param("aggregationLevel", level)
                                .param("pageNumber", "1").param("pageSize", pageSize)
                                .param("excludeCancelledTransactions", excl);
                        String key = endpoint + " level=" + level + " excl=" + excl + " pageSize=" + pageSize;
                        out.put(key, mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                                .getContentAsString());
                    }
                }
            }
        }
        return out;
    }

    private static ProjectionTransaction row(short ownerType, SourceKind source, String sourceId, String at,
            String batchId, String subBatchId, String amount, String cancels) {
        return new ProjectionTransaction(ownerType, PLAYER, source, sourceId, Instant.parse(at), "P10", "keno",
                batchId, subBatchId, cancels == null ? TxTypes.PLAY : TxTypes.CANCEL, new BigDecimal(amount), "EUR",
                null, cancels, null);
    }
}
