package wqs.projection.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * A decoded {@code walAccountTransactions} stream message (spec 4.1). Only the fields the
 * projection uses are kept; the rest of the wire message is dropped, never carried along.
 *
 * <p>{@code userType} and {@code userId} come from {@code userContext.ownerId} =
 * {@code "{userType}-{userId}"}; deciding what to do with a non-player owner is the projection's
 * job, not the decoder's. {@code type} is the wire code ({@code TT_BUY}); the mapper turns it
 * into a "wqTxType" id. {@code transactionReference} is the raw {@code references.transactionReference}
 * -- the producer self-references when there is no link, so it is only a cancel target per the
 * mapper's rule.
 *
 * <p>{@code detail} holds only the allowlisted fields (plan D9), under dotted keys such as
 * {@code accountContext.accountType}. It is never "the rest of the message".
 */
public record StreamPayload(
        String id,
        int userType,
        long userId,
        Instant created,
        String type,
        String productId,
        String productType,
        String batchId,
        String subBatchId,
        BigDecimal amount,
        String currency,
        BigDecimal totalPostBalance,
        String transactionReference,
        Map<String, String> detail) {

    public StreamPayload {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
