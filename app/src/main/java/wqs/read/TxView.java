package wqs.read;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One level-0 transaction as the contract returns it. {@code kind} is the source ({@code
 * TRANSACTION}, {@code PAYMENT}, ...). Null fields are omitted, and {@code status} is deliberately
 * absent (D12). {@code detail} is allowlisted JSON text written raw.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxView(
        String txId,
        String kind,
        Instant created,
        String productId,
        String productType,
        String transactionType,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        boolean cancelled,
        String batchId,
        String subBatchId,
        @JsonRawValue String detail) implements HighLevelEntry {

    static TxView of(TransactionEntry e) {
        return new TxView(e.sourceId(), e.kind(), e.occurredAt(), e.productId(), e.productType(),
                e.transactionType(), e.amount(), e.currency(), e.balanceAfter(), e.cancelled(), e.batchId(),
                e.subBatchId(), e.detail());
    }
}
