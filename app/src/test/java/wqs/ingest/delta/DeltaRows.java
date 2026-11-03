package wqs.ingest.delta;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wire rows for {@code /wallet/delta} tests: only the fields each source's decoder reads (the union
 * structure's other fields are null on the wire and absent here, which the decoder treats alike).
 */
final class DeltaRows {

    private DeltaRows() {}

    static String page(String... rows) {
        return List.of(rows).stream().collect(Collectors.joining(",", "[", "]"));
    }

    static String page(List<String> rows) {
        return page(rows.toArray(String[]::new));
    }

    /** A claimable prize for player {@code userId}; {@code batchId} null for an unsessioned one. */
    static String prize(String id, long userId, Instant created, Instant updated, String status,
            String productType, String batchId) {
        return prize(id, userId, created, updated, status, productType, batchId, "");
    }

    /** A prize in play {@code subBatchId} of session {@code batchId}. */
    static String prize(String id, long userId, Instant created, Instant updated, String status,
            String productType, String batchId, String subBatchId) {
        return "{\"source\":\"walClaimablePrizes\",\"id\":" + id + ",\"playerId\":\"" + userId + "\","
                + "\"created\":\"" + created + "\",\"updated\":\"" + updated + "\","
                + "\"prizeAmount\":150.000,\"currency\":\"EUR\",\"status\":\"" + status + "\","
                + "\"productId\":\"LOTTO6\",\"productType\":\"" + productType + "\","
                + "\"batchId\":" + (batchId == null ? "null" : "\"" + batchId + "\"") + ",\"subBatchId\":\"" + subBatchId + "\"}";
    }

    static String payment(String paymentId, int ownerType, long ownerId, Instant created, Instant updated, int status) {
        return payment(paymentId, ownerType, ownerId, created, updated, status, 2);
    }

    static String payment(String paymentId, int ownerType, long ownerId, Instant created, Instant updated,
            int status, int paymentInOut) {
        return "{\"source\":\"walPayments\",\"paymentId\":" + paymentId + ",\"ownerType\":" + ownerType + ","
                + "\"ownerId\":\"" + ownerId + "\",\"created\":\"" + created + "\",\"updated\":\"" + updated + "\","
                + "\"paymentInOut\":" + paymentInOut + ",\"paymentType\":\"WITHDRAWAL\",\"amount\":25.500,"
                + "\"currency\":\"EUR\",\"status\":" + status + ",\"payCardNumber\":\"ENC:4f1c9a0b7e2d\","
                + "\"xmlData\":\"<payment/>\"}";
    }

    /** A count-based limit: no amount, no currency (stored as XXX). */
    static String limit(String id, long userId, Instant created, Instant updated) {
        return "{\"source\":\"walLimits\",\"id\":" + id + ",\"userType\":1,\"userId\":\"" + userId + "\","
                + "\"created\":\"" + created + "\",\"updated\":\"" + updated + "\","
                + "\"limitType\":\"PLAY_COUNT\",\"quantityType\":\"COUNT\",\"period\":\"DAILY\",\"count\":5,"
                + "\"amount\":null,\"currency\":null}";
    }
}
