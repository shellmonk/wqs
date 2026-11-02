-- Level-1/2 page index: one narrow row per session and per unsessioned item, merged at WRITE
-- time so paging stays a single covering index scan (spec 5.6).
--
-- A page index, not a payload store: it carries only what ordering, filtering and counting need.
-- Payload is fetched by "refId" during assembly.
create table "wqWebHighLevel" (
    "userId"               bigint      not null,
    "occurredAt"           timestamptz not null,  -- session "startedAt", or the item's "occurredAt"
    "kind"                 smallint    not null,  -- 0 = SESSION, otherwise the source code
    "refId"                text        not null,  -- "batchId" for a session, "sourceId" for an item
    "productId"            int,
    "currency"             char(3)     not null,
    "visibleExclCancelled" boolean     not null default true,
    primary key ("userId", "occurredAt", "kind", "refId")
) partition by range ("occurredAt");

create table "wqCcHighLevel" (
    "userId"               bigint      not null,
    "occurredAt"           timestamptz not null,
    "kind"                 smallint    not null,
    "refId"                text        not null,
    "productId"            int,
    "currency"             char(3)     not null,
    "visibleExclCancelled" boolean     not null default true,
    primary key ("userId", "occurredAt", "kind", "refId")
) partition by range ("occurredAt");
