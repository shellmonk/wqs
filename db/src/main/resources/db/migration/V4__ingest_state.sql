-- Consumer position. Stored here, NOT in the broker, so that projection state and consumer
-- position advance or roll back in the same transaction -- the effectively-once property
-- described in spec section 6.2.
create table "wqStreamOffset" (
    "stream"     text        not null,
    "offsetVal"  bigint      not null,
    "updatedAt"  timestamptz not null default now(),
    primary key ("stream")
);

-- Poison events, retained for manual replay. Written after N failed attempts, after which the
-- offset advances and an alarm fires: one malformed event must not stop history for every user
-- (spec section 6.4).
create table "wqDeadLetter" (
    "deadLetterId"  bigserial   primary key,
    "stream"        text,
    "streamOffset"  bigint,
    "source"        smallint,
    "sourceId"      text,
    "payload"       jsonb       not null,
    "error"         text        not null,
    "attempts"      int         not null,
    "failedAt"      timestamptz not null default now()
);

create index "wqDeadLetter_failedAt_idx" on "wqDeadLetter" ("failedAt" desc);
