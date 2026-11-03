package wqs.read;

import java.time.Instant;

/** Position of a transaction in the total order ("occurredAt" desc, "transactionId" desc). */
public record TransactionKey(Instant occurredAt, long transactionId) {}
