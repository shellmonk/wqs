-- Cancels whose original has not yet arrived. A cancel can precede its target: different
-- transport, no global ordering (spec 6.3). Parking them here is what makes ingestion
-- order-independent.
--
-- A cancel targets an item of its OWN source (a TRANSACTION cancel cancels a TRANSACTION, a
-- PAYMENT cancel a PAYMENT), so the target is ("source", "cancelsSourceId") -- the same key as
-- "wqItemLocator". sourceId alone is not unique across sources. The same-source assumption is
-- to be confirmed with the producer (O-13).
--
-- Every ProjectionItem field of the cancel is stored, so a drained cancel is rebuilt exactly as
-- it arrived (its real source included). The primary key makes re-parking the same cancel a
-- no-op.
create table "wqPendingCancel" (
    "source"          smallint      not null,
    "cancelSourceId"  text          not null,
    "cancelsSourceId" text          not null,
    "userId"          bigint        not null,
    "occurredAt"      timestamptz   not null,
    "productId"       int,
    "batchId"         text,
    "subBatchId"      text,
    "type"            smallint      not null,
    "amount"          numeric(20,4) not null,
    "currency"        char(3)       not null,
    "balanceAfter"    numeric(20,4),
    "detail"          text,
    "parkedAt"        timestamptz   not null default now(),
    primary key ("source", "cancelSourceId")
);

create index "wqPendingCancel_target_idx" on "wqPendingCancel" ("source", "cancelsSourceId");
