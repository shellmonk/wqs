-- Covering indexes for the level-0 and high-level read paths (spec 7.7).
-- Created on the partitioned parents; Postgres propagates them to existing and future partitions.
create index "wqWebItem_cover" on "wqWebItem" ("userId", "occurredAt" desc, "itemId" desc) include ("cancelled", "productId");
create index "wqCcItem_cover"  on "wqCcItem"  ("userId", "occurredAt" desc, "itemId" desc) include ("cancelled", "productId");
create index "wqWebHighLevel_cover" on "wqWebHighLevel" ("userId", "occurredAt" desc) include ("kind", "refId", "productId", "visibleExclCancelled");
create index "wqCcHighLevel_cover"  on "wqCcHighLevel"  ("userId", "occurredAt" desc) include ("kind", "refId", "productId", "visibleExclCancelled");
