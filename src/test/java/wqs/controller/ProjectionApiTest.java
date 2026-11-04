package wqs.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.IntegrationTest;
import wqs.dto.IncomingTransaction;
import wqs.dto.Source;
import wqs.security.HeaderPlayerIdResolver;
import wqs.service.TransactionIngestService;

/** The /cc and /web projection endpoints over HTTP, against rows written by the real ingest service. */
class ProjectionApiTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000);
    private static final String RANGE = "from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z";

    @Autowired TestRestTemplate http;
    @Autowired TransactionIngestService ingest;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    private final long player = IDS.incrementAndGet();

    // --- level 0 ---

    @Test
    void level_0_lists_visible_rows_newest_first() throws Exception {
        String older = store(row("TT_TRANSFER_OUT", "-12", "2026-10-02T10:00:00Z", null, null));
        String newer = store(row("TT_BUY", "-2", "2026-10-03T10:00:00Z", null, null));
        store(row("PRIZE", "0", "2026-10-04T10:00:00Z", null, null)); // zero amount: stored, never shown

        JsonNode page = cc(RANGE);
        assertThat(page.get("totalRowCount").asLong()).isEqualTo(2);
        assertThat(page.get("pagesAvailable").asLong()).isEqualTo(1);
        assertThat(txIds(page.get("data"))).containsExactly(newer, older);
        JsonNode first = page.get("data").get(0);
        assertThat(first.get("kind").asText()).isEqualTo("TRANSACTION");
        assertThat(first.get("transactionType").asText()).isEqualTo("TT_BUY");
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("-2");
        assertThat(first.get("created").asText()).isEqualTo("2026-10-03T10:00:00Z");
        assertThat(first.has("batchId")).isFalse(); // nulls are omitted
    }

    @Test
    void level_0_pages_with_a_plain_count() throws Exception {
        for (int day = 1; day <= 5; day++) {
            store(row("TT_BUY", "-1", "2026-10-0" + day + "T10:00:00Z", null, null));
        }
        JsonNode last = cc(RANGE + "&pageSize=2&pageNumber=3");
        assertThat(last.get("totalRowCount").asLong()).isEqualTo(5);
        assertThat(last.get("pagesAvailable").asLong()).isEqualTo(3);
        assertThat(last.get("pageNumber").asInt()).isEqualTo(3);
        assertThat(last.get("data")).hasSize(1);
        assertThat(last.get("data").get(0).get("created").asText()).isEqualTo("2026-10-01T10:00:00Z");
    }

    @Test
    void level_0_range_is_from_inclusive_to_exclusive() throws Exception {
        String atFrom = store(row("TT_BUY", "-1", "2026-10-01T00:00:00Z", null, null));
        store(row("TT_BUY", "-1", "2026-11-01T00:00:00Z", null, null));

        assertThat(txIds(cc(RANGE).get("data"))).containsExactly(atFrom);
    }

    @Test
    void level_0_filters_by_product_type_and_transaction_type() throws Exception {
        String instantsBuy = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null, "instants"));
        store(row("TT_BUY", "-1", "2026-10-03T10:00:00Z", null, null, "lotto"));
        store(row("PRIZE", "3", "2026-10-04T10:00:00Z", null, null, "instants"));

        assertThat(txIds(cc(RANGE + "&productTypes=instants&transactionTypes=TT_BUY").get("data")))
                .containsExactly(instantsBuy);
        assertThat(cc(RANGE + "&productTypes=instants,lotto").get("totalRowCount").asLong()).isEqualTo(3);
    }

    @Test
    void web_shows_only_transactions_and_claimable_prizes_and_cc_shows_every_source() throws Exception {
        String bet = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        String payment = store(withSource(row("PT_BUY", "-5", "2026-10-03T10:00:00Z", null, null), Source.PAYMENT));

        assertThat(txIds(web(player, RANGE).get("data"))).containsExactly(bet);
        assertThat(txIds(cc(RANGE).get("data"))).containsExactly(payment, bet);
    }

    @Test
    void another_owner_type_with_the_same_user_id_is_never_shown() throws Exception {
        store(withOwnerType(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null), (short) 2));

        assertThat(cc(RANGE).get("totalRowCount").asLong()).isZero();
        assertThat(web(player, RANGE).get("totalRowCount").asLong()).isZero();
    }

    @Test
    void cancelled_rows_are_left_out_on_request() throws Exception {
        String kept = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        String cancelled = store(row("TT_BUY", "-1", "2026-10-03T10:00:00Z", null, null));
        jdbc.sql("update \"wqPrjTransaction\" set \"cancelled\" = true where \"sourceId\" = :id").param("id", cancelled).update();

        assertThat(txIds(cc(RANGE).get("data"))).containsExactly(cancelled, kept);
        assertThat(txIds(cc(RANGE + "&excludeCancelledTransactions=true").get("data"))).containsExactly(kept);
    }

    // --- levels 1 and 2 ---

    @Test
    void level_1_merges_sessions_with_rows_that_have_none() throws Exception {
        String batch = "GS-" + UUID.randomUUID();
        String transfer = store(row("TT_TRANSFER_OUT", "-12", "2026-10-29T11:22:04Z", null, null));
        String bet = store(row("TT_BUY", "-2", "2026-10-26T11:36:17Z", batch, "P1"));
        String prize = store(row("PRIZE", "5", "2026-10-26T11:36:23Z", batch, "P1"));
        store(row("PRIZE", "0", "2026-10-26T11:36:30Z", batch, "P1")); // zero: not shown, not counted
        String early = store(row("TT_BUY", "-1", "2026-10-20T09:00:00Z", null, null));

        JsonNode page = cc(RANGE + "&aggregationLevel=1");
        assertThat(page.get("totalRowCount").asLong()).isEqualTo(3); // one session + two rows without one
        JsonNode data = page.get("data");
        assertThat(data.get(0).get("txId").asText()).isEqualTo(transfer);
        JsonNode session = data.get(1);
        assertThat(session.get("kind").asText()).isEqualTo("SESSION");
        assertThat(session.get("batchId").asText()).isEqualTo(batch);
        assertThat(session.get("created").asText()).isEqualTo("2026-10-26T11:36:17Z");
        assertThat(session.get("totalPlay").decimalValue()).isEqualByComparingTo("2");
        assertThat(session.get("totalWin").decimalValue()).isEqualByComparingTo("5");
        assertThat(session.get("amount").decimalValue()).isEqualByComparingTo("3");
        assertThat(txIds(session.get("txs"))).containsExactly(prize, bet);
        assertThat(session.has("subitems")).isFalse();
        assertThat(data.get(2).get("txId").asText()).isEqualTo(early);
    }

    @Test
    void web_level_1_never_lists_a_payment() throws Exception {
        String bet = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        store(withSource(row("PT_BUY", "-5", "2026-10-03T10:00:00Z", null, null), Source.PAYMENT));

        JsonNode page = web(player, RANGE + "&aggregationLevel=1");
        assertThat(page.get("totalRowCount").asLong()).isEqualTo(1);
        assertThat(txIds(page.get("data"))).containsExactly(bet);
        assertThat(cc(RANGE + "&aggregationLevel=1").get("totalRowCount").asLong()).isEqualTo(2);
    }

    @Test
    void level_1_pages_count_top_level_entries() throws Exception {
        for (int day = 1; day <= 3; day++) {
            String batch = "GS-" + UUID.randomUUID();
            store(row("TT_BUY", "-1", "2026-10-0" + day + "T10:00:00Z", batch, "P1"));
            store(row("TT_BUY", "-1", "2026-10-0" + day + "T10:01:00Z", batch, "P1"));
        }
        store(row("TT_BUY", "-1", "2026-10-05T10:00:00Z", null, null));

        JsonNode second = cc(RANGE + "&aggregationLevel=1&pageSize=3&pageNumber=2");
        assertThat(second.get("totalRowCount").asLong()).isEqualTo(4);
        assertThat(second.get("pagesAvailable").asLong()).isEqualTo(2);
        assertThat(second.get("data")).hasSize(1);
        assertThat(second.get("data").get(0).get("created").asText()).isEqualTo("2026-10-01T10:00:00Z");
    }

    @Test
    void level_2_nests_plays_under_their_session() throws Exception {
        String batch = "GS-" + UUID.randomUUID();
        String bet1 = store(row("TT_BUY", "-2", "2026-10-26T11:36:17Z", batch, "P1"));
        String win1 = store(row("PRIZE", "5", "2026-10-26T11:36:23Z", batch, "P1"));
        String bet2 = store(row("TT_BUY", "-2", "2026-10-26T11:38:02Z", batch, "P2"));
        String fee = store(row("TT_BUY", "-1", "2026-10-26T11:39:00Z", batch, null)); // in the session, in no play

        JsonNode session = cc(RANGE + "&aggregationLevel=2").get("data").get(0);
        assertThat(session.get("playCount").asInt()).isEqualTo(2);
        assertThat(txIds(session.get("txs"))).containsExactly(fee);
        JsonNode plays = session.get("subitems");
        assertThat(plays).hasSize(2);
        assertThat(plays.get(0).get("subBatchId").asText()).isEqualTo("P2");
        assertThat(txIds(plays.get(0).get("txs"))).containsExactly(bet2);
        assertThat(plays.get(1).get("playTimestamp").asText()).isEqualTo("2026-10-26T11:36:17Z");
        assertThat(plays.get(1).get("playAmount").decimalValue()).isEqualByComparingTo("2");
        assertThat(plays.get(1).get("winAmount").decimalValue()).isEqualByComparingTo("5");
        assertThat(txIds(plays.get(1).get("txs"))).containsExactly(win1, bet1);
    }

    @Test
    void level_1_type_filter_lists_only_sessions_with_a_matching_row() throws Exception {
        String withPrize = "GS-" + UUID.randomUUID();
        store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", withPrize, "P1"));
        String prize = store(row("PRIZE", "4", "2026-10-02T10:01:00Z", withPrize, "P1"));
        store(row("TT_BUY", "-1", "2026-10-03T10:00:00Z", "GS-" + UUID.randomUUID(), "P1"));

        JsonNode page = cc(RANGE + "&aggregationLevel=1&transactionTypes=PRIZE");
        assertThat(page.get("totalRowCount").asLong()).isEqualTo(1);
        assertThat(page.get("data").get(0).get("batchId").asText()).isEqualTo(withPrize);
        assertThat(txIds(page.get("data").get(0).get("txs"))).containsExactly(prize);
    }

    // --- validation and identity ---

    @Test
    void invalid_parameters_are_400_naming_the_parameter() {
        assertProblem(get("/cc/wallet-data/rest/v1/projection/" + player + "?to=2026-11-01T00:00:00Z", null), 400, "from");
        assertProblem(get(ccPath() + RANGE + "&aggregationLevel=3", null), 400, "aggregationLevel");
        assertProblem(get(ccPath() + RANGE + "&pageSize=1001", null), 400, "pageSize");
        assertProblem(get(ccPath() + RANGE + "&aggregationLevel=1&pageSize=101", null), 400, "pageSize");
        assertProblem(get(ccPath() + RANGE + "&pageNumber=0", null), 400, "pageNumber");
        assertProblem(get(ccPath() + "from=yesterday&to=2026-11-01T00:00:00Z", null), 400, "from");
        assertProblem(get(ccPath() + "from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z", null), 400, "from");
        assertProblem(get("/cc/wallet-data/rest/v1/projection/-5?" + RANGE, null), 400, "playerId");
    }

    @Test
    void web_without_a_player_is_401() {
        ResponseEntity<String> response = get("/web/wallet-data/rest/v1/projection?" + RANGE, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // --- helpers ---

    private String store(IncomingTransaction t) {
        tx.executeWithoutResult(status -> ingest.ingest(t));
        return t.sourceId();
    }

    private IncomingTransaction row(String type, String amount, String at, String batch, String subBatch) {
        return row(type, amount, at, batch, subBatch, "instants");
    }

    private IncomingTransaction row(String type, String amount, String at, String batch, String subBatch, String productType) {
        return new IncomingTransaction((short) 1, player, Instant.parse(at), Source.TRANSACTION, Long.toString(IDS.incrementAndGet()),
                type, new BigDecimal(amount), "EUR", null, "GAME", productType, batch, subBatch, null, Map.of("channel", "WEB"));
    }

    private static IncomingTransaction withSource(IncomingTransaction t, Source source) {
        return new IncomingTransaction(t.ownerType(), t.userId(), t.occurredAt(), source, t.sourceId(), t.type(), t.amount(),
                t.currency(), t.balanceAfter(), t.productId(), t.productType(), t.batchId(), t.subBatchId(),
                t.transactionReference(), t.detail());
    }

    private static IncomingTransaction withOwnerType(IncomingTransaction t, short ownerType) {
        return new IncomingTransaction(ownerType, t.userId(), t.occurredAt(), t.source(), t.sourceId(), t.type(), t.amount(),
                t.currency(), t.balanceAfter(), t.productId(), t.productType(), t.batchId(), t.subBatchId(),
                t.transactionReference(), t.detail());
    }

    private String ccPath() {
        return "/cc/wallet-data/rest/v1/projection/" + player + "?";
    }

    private JsonNode cc(String query) throws Exception {
        return ok(get(ccPath() + query, null));
    }

    private JsonNode web(long playerId, String query) throws Exception {
        return ok(get("/web/wallet-data/rest/v1/projection?" + query, Long.toString(playerId)));
    }

    private ResponseEntity<String> get(String path, String playerHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (playerHeader != null) {
            headers.set(HeaderPlayerIdResolver.HEADER, playerHeader);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode ok(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return mapper.readTree(response.getBody());
    }

    private void assertProblem(ResponseEntity<String> response, int status, String parameter) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"parameter\":\"" + parameter + "\"");
    }

    private static List<String> txIds(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("txId").asText()));
        return ids;
    }
}
