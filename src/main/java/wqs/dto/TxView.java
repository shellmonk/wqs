package wqs.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One transaction as the contract returns it. {@code txId} is the wallet's id; {@code kind} the
 * source ({@code TRANSACTION}, {@code PAYMENT}, ...). Null fields are omitted; {@code status} is
 * not part of the response yet.
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
        Map<String, String> detail) implements HighLevelEntry {}
