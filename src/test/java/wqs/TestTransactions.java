package wqs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import wqs.dto.IncomingTransaction;
import wqs.dto.Source;

/**
 * Builds {@link IncomingTransaction}s for tests: a stream transaction of 1 EUR played (TT_BUY) on
 * "GAME"/"instants", with no session, unless told otherwise. Each builder fixes its source id when
 * created, so building it twice gives the same row (a redelivery).
 */
public final class TestTransactions {

    private TestTransactions() {}

    public static Builder transaction(String ownerId) {
        return new Builder(ownerId);
    }

    public static final class Builder {

        private String ownerId;
        private Instant created = Instant.parse("2026-10-01T00:00:00Z");
        private Source source = Source.TRANSACTION;
        private final String sourceId = UUID.randomUUID().toString();
        private String transactionType = "TT_BUY";
        private BigDecimal amount = new BigDecimal("-1");
        private String currency = "EUR";
        private String productId = "GAME";
        private String productType = "instants";
        private String batchId;
        private String subBatchId;

        private Builder(String ownerId) {
            this.ownerId = ownerId;
        }

        public Builder owner(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder at(String instant) {
            this.created = Instant.parse(instant);
            return this;
        }

        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        public Builder transactionType(String transactionType) {
            this.transactionType = transactionType;
            return this;
        }

        public Builder amount(String amount) {
            this.amount = new BigDecimal(amount);
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder productId(String productId) {
            this.productId = productId;
            return this;
        }

        public Builder productType(String productType) {
            this.productType = productType;
            return this;
        }

        /** In this session, and in this play of it ({@code subBatchId} may be null: in no play). */
        public Builder session(String batchId, String subBatchId) {
            this.batchId = batchId;
            this.subBatchId = subBatchId;
            return this;
        }

        public String sourceId() {
            return sourceId;
        }

        public IncomingTransaction build() {
            return new IncomingTransaction(ownerId, created, source, sourceId, transactionType, amount, currency, null,
                    productId, productType, batchId, subBatchId, null, Map.of("channel", "WEB"));
        }
    }
}
