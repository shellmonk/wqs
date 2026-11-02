package wqs.read;

import java.time.Instant;

/** Position of an item in the total order ("occurredAt" desc, "itemId" desc). */
public record ItemKey(Instant occurredAt, long itemId) {}
