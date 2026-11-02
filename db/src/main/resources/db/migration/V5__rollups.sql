-- Locates a session or play by its business key, so a later transaction joining an existing
-- group can find the row whose primary key includes the partition key. Same pattern and same
-- reason as "wqItemLocator" (spec 5.4). "subBatchId" = '' identifies the session itself.
create table "wqGroupLocator" (
    "userId"     bigint      not null,
    "batchId"    text        not null,
    "subBatchId" text        not null default '',
    "startedAt"  timestamptz not null,
    primary key ("userId", "batchId", "subBatchId")
);

create table "wqSession" (
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                int,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "playCount"                int           not null default 0,
    "playCountExclCancelled"   int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    primary key ("userId", "startedAt", "batchId")
) partition by range ("startedAt");

create table "wqPlay" (
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "subBatchId"               text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                int,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    primary key ("userId", "startedAt", "batchId", "subBatchId")
) partition by range ("startedAt");
