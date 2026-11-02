-- Product dimension. "category" keeps the eInstant view data-driven rather than a
-- hardcoded id list in Java (spec 7.2).
create table "wqProduct" (
    "productId" int         primary key,
    "code"      text        not null unique,
    "name"      text        not null,
    "category"  smallint    not null          -- 1 = EINSTANT, 2 = LOTTERY, ...
);

-- Global sourceId -> partition locator. Deliberately NOT partitioned: a cancel event
-- references an original by sourceId with no timestamp, so resolving it against a
-- partitioned index would probe every monthly partition (spec 5.4).
--
-- Doubles as the idempotency gate for ingestion: an ON CONFLICT DO NOTHING insert that
-- conflicts means the event is already applied.
create table "wqItemLocator" (
    "source"     smallint    not null,
    "sourceId"   text        not null,
    "userId"     bigint      not null,
    "occurredAt" timestamptz not null,
    "itemId"     bigint      not null,
    primary key ("source", "sourceId")
);

create index "wqItemLocator_user_idx" on "wqItemLocator" ("userId");
