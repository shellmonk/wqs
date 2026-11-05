package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A transaction arriving from a feed, before it is stored in {@code wqPrjTransactions}; every feed
 * maps to it. {@code userType} and {@code userId} are not here: the table derives them from
 * {@code ownerId}.
 */
public record IncomingTransaction(
        String ownerId,
        Instant created,
        Source source,
        String sourceId,
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
        boolean monetary,
        String description,
        String fromDescription,
        String toDescription,
        Long paymentId,
        Long claimId,
        String extTrxId,
        String extSystemId,
        String transactionReference,
        String channel,
        Instant issued) {

    /**
     * A zero-amount stream transaction: stored, never shown, and adds nothing to session or play
     * totals. Mirrors the generated column {@code zeroAmount}.
     */
    public boolean zeroAmount() {
        return source == Source.TRANSACTION && amount.signum() == 0;
    }
}
