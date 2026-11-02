-- Level-0 rows for the user-facing views: transactions and claimable prizes (spec 4).
create table "wqWebItem" (
    "userId"       bigint        not null,
    "itemId"       bigint        not null,
    "source"       smallint      not null,
    "sourceId"     text          not null,
    "occurredAt"   timestamptz   not null,
    "productId"    int,
    "batchId"      text,
    "subBatchId"   text,
    "type"         smallint      not null,
    "amount"       numeric(20,4) not null,
    "currency"     char(3)       not null,
    "balanceAfter" numeric(20,4),
    "cancelled"    boolean       not null default false,
    "cancelledBy"  text,
    "cancels"      text,
    "detail"       jsonb,
    "ingestedAt"   timestamptz   not null default now(),
    primary key ("userId", "occurredAt", "itemId")
) partition by range ("occurredAt");

-- Level-0 rows for the operator unified view: all five sources (spec 4).
create table "wqCcItem" (
    "userId"       bigint        not null,
    "itemId"       bigint        not null,
    "source"       smallint      not null,
    "sourceId"     text          not null,
    "occurredAt"   timestamptz   not null,
    "productId"    int,
    "batchId"      text,
    "subBatchId"   text,
    "type"         smallint      not null,
    "amount"       numeric(20,4) not null,
    "currency"     char(3)       not null,
    "balanceAfter" numeric(20,4),
    "cancelled"    boolean       not null default false,
    "cancelledBy"  text,
    "cancels"      text,
    "detail"       jsonb,
    "ingestedAt"   timestamptz   not null default now(),
    primary key ("userId", "occurredAt", "itemId")
) partition by range ("occurredAt");

create sequence "wqItemIdSeq" as bigint;
