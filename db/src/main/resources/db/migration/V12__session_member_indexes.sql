-- Level-1/2 assembly (spec 7.3 "each bounded by the page size"; final review F1): a page's
-- sessions' items and plays are fetched per session by batch id. Without these the only usable
-- index is ("userId", "occurredAt"/"startedAt"), so a long-lived session made the fetch read every
-- row of the user inside its window. Partial on sessioned items: unsessioned rows never probe it.
create index "wqWebItem_batch" on "wqWebItem" ("userId", "batchId", "occurredAt") where "batchId" is not null;
create index "wqCcItem_batch"  on "wqCcItem"  ("userId", "batchId", "occurredAt") where "batchId" is not null;
create index "wqPlay_batch"    on "wqPlay"    ("userId", "batchId", "startedAt");
