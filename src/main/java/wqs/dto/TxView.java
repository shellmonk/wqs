package wqs.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One transaction as the contract returns it, in the contract's order. {@code accountTransactionId}
 * is the source's own id ({@code "sourceId"}), so it is a string: other sources will come.
 * {@code kind} is the source ({@code TRANSACTION}, {@code PAYMENT}, ...) and tells a transaction
 * from a session at levels 1-2; {@code cancelled} shows a cancellation when cancelled rows are
 * included. Null fields are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxView(
        String kind,
        Instant created,
        String accountTransactionId,
        Integer userType,
        String userId,
        String accountType,
        String accountNumber,
        String accountId,
        String batchId,
        String subBatchId,
        Boolean batchEnded,
        String transactionType,
        String transactionSubtype,
        String productId,
        String productType,
        String currency,
        BigDecimal amount,
        BigDecimal totalPostBalance,
        BigDecimal reservedAmount,
        BigDecimal nonWithdrawableAmount,
        Boolean monetary,
        String description,
        String fromDescription,
        String toDescription,
        Long paymentId,
        Long claimId,
        String extTrxId,
        String extSystemId,
        String channel,
        Instant issued,
        boolean cancelled) implements HighLevelEntry {}
