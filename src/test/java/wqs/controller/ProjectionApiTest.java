package wqs.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
import wqs.TestTransactions;
import wqs.dto.Source;
import wqs.security.HeaderOwnerIdResolver;
import wqs.service.ProjectionWriter;

/** The /cc and /web projection endpoints over HTTP, against rows written by the real ingest service. */
class ProjectionApiTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 1000);
    private static final String RANGE = "from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z";

    @Autowired TestRestTemplate restTemplate;
    @Autowired ProjectionWriter projectionWriter;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcClient jdbcClient;
    @Autowired ObjectMapper objectMapper;

    private final long number = IDS.incrementAndGet();
    private final String owner = "1-" + number;

    // --- level 0 ---

    @Test
    void level0ListsVisibleRowsNewestFirst() throws Exception {
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
    void level0PagesWithAPlainCount() throws Exception {
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
    void level0RangeIsFromInclusiveToExclusive() throws Exception {
        String atFrom = store(row("TT_BUY", "-1", "2026-10-01T00:00:00Z", null, null));
        store(row("TT_BUY", "-1", "2026-11-01T00:00:00Z", null, null));

        assertThat(txIds(cc(RANGE).get("data"))).containsExactly(atFrom);
    }

    @Test
    void level0FiltersByProductTypeAndTransactionType() throws Exception {
        String instantsBuy = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null).productType("instants"));
        store(row("TT_BUY", "-1", "2026-10-03T10:00:00Z", null, null).productType("lotto"));
        store(row("PRIZE", "3", "2026-10-04T10:00:00Z", null, null).productType("instants"));

        assertThat(txIds(cc(RANGE + "&productTypes=instants&transactionTypes=TT_BUY").get("data")))
                .containsExactly(instantsBuy);
        assertThat(cc(RANGE + "&productTypes=instants,lotto").get("totalRowCount").asLong()).isEqualTo(3);
    }

    @Test
    void webShowsOnlyTransactionsAndClaimablePrizesAndCcShowsEverySource() throws Exception {
        String bet = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        String payment = store(row("PT_BUY", "-5", "2026-10-03T10:00:00Z", null, null).source(Source.PAYMENT));

        assertThat(txIds(web(owner, RANGE).get("data"))).containsExactly(bet);
        assertThat(txIds(cc(RANGE).get("data"))).containsExactly(payment, bet);
    }

    @Test
    void anotherOwnerWithTheSameNumberIsNeverShown() throws Exception {
        store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null).owner("2-" + number));

        assertThat(cc(RANGE).get("totalRowCount").asLong()).isZero();
        assertThat(web(owner, RANGE).get("totalRowCount").asLong()).isZero();
    }

    @Test
    void cancelledRowsAreLeftOutOnRequest() throws Exception {
        String kept = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        String cancelled = store(row("TT_BUY", "-1", "2026-10-03T10:00:00Z", null, null));
        jdbcClient.sql("update \"wqPrjTransactions\" set \"cancelled\" = true where \"sourceId\" = :id").param("id", cancelled).update();

        assertThat(txIds(cc(RANGE).get("data"))).containsExactly(cancelled, kept);
        assertThat(txIds(cc(RANGE + "&excludeCancelledTransactions=true").get("data"))).containsExactly(kept);
    }

    // --- levels 1 and 2 ---

    @Test
    void level1MergesSessionsWithRowsThatHaveNone() throws Exception {
        String batch = "GS-" + UUID.randomUUID();
        String transfer = store(row("TT_TRANSFER_OUT", "-12", "2026-10-29T11:22:04Z", null, null));
        String bet = store(row("TT_BUY", "-2", "2026-10-26T11:36:17Z", batch, "P1"));
        String prize = store(row("PRIZE", "5", "2026-10-26T11:36:23Z", batch, "P1"));
        store(row("PRIZE", "0", "2026-10-26T11:36:30Z", batch, "P1")); // zero: not shown, not counted
        String early = store(row("TT_BUY", "-1", "2026-10-20T09:00:00Z", null, null));

        JsonNode page = cc(RANGE + "&aggregationLevel=1");
        assertThat(page.get("totalRowCount").asLong()).isEqualTo(3); // one session + two rows without one
        JsonNode data = page.get("data");
        assertThat(data.get(0).get("accountTransactionId").asText()).isEqualTo(transfer);
        JsonNode session = data.get(1);
        assertThat(session.get("kind").asText()).isEqualTo("SESSION");
        assertThat(session.get("batchId").asText()).isEqualTo(batch);
        assertThat(session.get("created").asText()).isEqualTo("2026-10-26T11:36:17Z");
        assertThat(session.get("totalPlay").decimalValue()).isEqualByComparingTo("2");
        assertThat(session.get("totalWin").decimalValue()).isEqualByComparingTo("5");
        assertThat(session.get("amount").decimalValue()).isEqualByComparingTo("3");
        assertThat(txIds(session.get("txs"))).containsExactly(prize, bet);
        assertThat(session.has("subitems")).isFalse();
        assertThat(data.get(2).get("accountTransactionId").asText()).isEqualTo(early);
    }

    @Test
    void webLevel1NeverListsAPayment() throws Exception {
        String bet = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        store(row("PT_BUY", "-5", "2026-10-03T10:00:00Z", null, null).source(Source.PAYMENT));

        JsonNode page = web(owner, RANGE + "&aggregationLevel=1");
        assertThat(page.get("totalRowCount").asLong()).isEqualTo(1);
        assertThat(txIds(page.get("data"))).containsExactly(bet);
        assertThat(cc(RANGE + "&aggregationLevel=1").get("totalRowCount").asLong()).isEqualTo(2);
    }

    @Test
    void level1PagesCountTopLevelEntries() throws Exception {
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
    void level2NestsPlaysUnderTheirSession() throws Exception {
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
    void level1TypeFilterListsOnlySessionsWithAMatchingRow() throws Exception {
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
    void invalidParametersAre400NamingTheParameter() {
        assertProblem(get("/cc/wallet-data/rest/v1/projection/" + owner + "?to=2026-11-01T00:00:00Z", null), 400, "from");
        assertProblem(get(ccPath() + RANGE + "&aggregationLevel=3", null), 400, "aggregationLevel");
        assertProblem(get(ccPath() + RANGE + "&pageSize=1001", null), 400, "pageSize");
        assertProblem(get(ccPath() + RANGE + "&aggregationLevel=1&pageSize=101", null), 400, "pageSize");
        assertProblem(get(ccPath() + RANGE + "&pageNumber=0", null), 400, "pageNumber");
        assertProblem(get(ccPath() + "from=yesterday&to=2026-11-01T00:00:00Z", null), 400, "from");
        assertProblem(get(ccPath() + "from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z", null), 400, "from");
        assertProblem(get("/cc/wallet-data/rest/v1/projection/1717?" + RANGE, null), 400, "ownerId");
        assertProblem(get("/cc/wallet-data/rest/v1/projection/a-1717?" + RANGE, null), 400, "ownerId");
    }

    @Test
    void theOwnerIdInThePathIsMatchedInItsCanonicalSpelling() throws Exception {
        String bet = store(row("TT_BUY", "-1", "2026-10-02T10:00:00Z", null, null));
        assertThat(txIds(ok(get("/cc/wallet-data/rest/v1/projection/01-" + number + "?" + RANGE, null)).get("data")))
                .containsExactly(bet);
    }

    @Test
    void webWithoutAnOwnerIs401() {
        ResponseEntity<String> response = get("/web/wallet-data/rest/v1/projection?" + RANGE, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // --- helpers ---

    private String store(TestTransactions.Builder row) {
        transactionTemplate.executeWithoutResult(status -> projectionWriter.write(row.build()));
        return row.sourceId();
    }

    /** A row of this test's owner; {@code batch} null means in no session. */
    private TestTransactions.Builder row(String transactionType, String amount, String at, String batch, String subBatch) {
        return TestTransactions.transaction(owner).transactionType(transactionType).amount(amount).at(at).session(batch, subBatch);
    }

    private String ccPath() {
        return "/cc/wallet-data/rest/v1/projection/" + owner + "?";
    }

    private JsonNode cc(String query) throws Exception {
        return ok(get(ccPath() + query, null));
    }

    private JsonNode web(String ownerId, String query) throws Exception {
        return ok(get("/web/wallet-data/rest/v1/projection?" + query, ownerId));
    }

    private ResponseEntity<String> get(String path, String ownerHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (ownerHeader != null) {
            headers.set(HeaderOwnerIdResolver.HEADER, ownerHeader);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode ok(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    private void assertProblem(ResponseEntity<String> response, int status, String parameter) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"parameter\":\"" + parameter + "\"");
    }

    private static List<String> txIds(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("accountTransactionId").asText()));
        return ids;
    }
}
