-- Phase 6: the four mutable polled sources (spec 4, 5.1; plan D5).

-- The stored version of a polled row: its source "updated" ("statusDate" for adjustment requests).
-- A delta row is applied only when strictly newer, a duplicate when equal, stale when older (D6).
-- Null for stream items, which are immutable and never versioned.
alter table "wqItemLocator"
    add column "versionAt" timestamptz;

-- The delta poller's cursor: the max version of the last applied page, committed in the same
-- transaction as that page's items (D7). The next poll asks for watermark - overlap.
create table "wqIngestWatermark" (
    "source"    text        not null,
    "watermark" timestamptz not null,
    "updatedAt" timestamptz not null default now(),
    primary key ("source")
);
