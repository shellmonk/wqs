package wqs.read;

import java.time.Instant;

/**
 * Position of a high-level item (a session or an unsessioned item, spec 5.6) in the level-1/2
 * total order ("occurredAt" desc, "kind" desc, "refId" desc) -- the page index's primary key,
 * less the user, read backwards. {@code kind} is 0 for a session, else the item's source code;
 * {@code refId} is the session's "batchId" or the item's "sourceId".
 */
public record HighLevelKey(Instant occurredAt, short kind, String refId) {}
