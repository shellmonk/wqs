-- WQS baseline schema. Every camelCase identifier is double-quoted (spec 3).
--
-- The owner-month model (plan 2026-11-04): seven tables. Everything is stored -- every source, every
-- owner type, zero-amount stream transactions -- and what an endpoint shows is decided at read time by
-- its read policy (wqs.read.ReadPolicy: sources, owner type, zero amounts), bound into every query.
-- Nothing here knows the endpoints. Products are strings on the wire ("productId" "TIKIKALACABANA",
-- "productType" "instants"); the eInstant view is selected by product type (wqs.einstant.product-types).

-- ---------------------------------------------------------------------------------------------
-- Level-0 transactions
-- ---------------------------------------------------------------------------------------------

-- Level-0 rows, every source stored once (spec 4). "type" holds the transaction-type code as it
-- arrived (wqs.domain.TransactionType, plan D1); a code the enum does not list is stored as-is and
-- rolls up as NEUTRAL. Both endpoints read this table, each through its read policy.
--
-- Every row is stored: non-player owners ("ownerType" <> 1) and zero-amount stream transactions too.
-- "zeroAmount" marks a stream transaction (source 1) of amount 0 (spec 4.1: never shown, counted
-- apart, outside session txCount -- plan D6). Polled rows are never zero-amount-hidden (spec 4), so
-- a zero-amount payment, adjustment, prize or limit is not "zeroAmount".
--
-- Duplicates are caught by the unique key ("source", "sourceId", "occurredAt") on this table, no
-- locator (plan D2): an insert ... on conflict do nothing that conflicts is a duplicate. sourceId alone
-- is not unique across sources. "occurredAt" is in the key because a unique key on a partitioned table
-- must contain the partition key; a stream transaction is immutable, so its redelivery always
-- conflicts.
--
-- "versionAt": the stored version of a polled row, its source "updated" ("statusDate" for
-- adjustment requests). A delta row is applied only when strictly newer, a duplicate when equal,
-- stale when older. Null for stream transactions, which are immutable and never versioned.
create table "wqPrjTransaction" (
    "ownerType"    smallint      not null,
    "userId"       bigint        not null,
    "transactionId"       bigint        not null,
    "source"       smallint      not null,
    "sourceId"     text          not null,
    "occurredAt"   timestamptz   not null,
    "productId"    text,
    "batchId"      text,
    "subBatchId"   text,
    "type"         text          not null,
    "amount"       numeric(20,4) not null,
    "currency"     char(3)       not null,
    "balanceAfter" numeric(20,4),
    "cancelled"    boolean       not null default false,
    "cancelledBy"  text,
    "cancels"      text,
    "detail"       jsonb,
    "ingestedAt"   timestamptz   not null default now(),
    "productType"  text,
    "zeroAmount"   boolean       not null generated always as ("amount" = 0 and "source" = 1) stored,
    "versionAt"    timestamptz,
    primary key ("ownerType", "userId", "occurredAt", "transactionId"),
    constraint "wqPrjTransaction_sourceKey" unique ("source", "sourceId", "occurredAt")
) partition by range ("occurredAt");

create sequence "wqTransactionIdSeq" as bigint;

-- ---------------------------------------------------------------------------------------------
-- Ingest state
-- ---------------------------------------------------------------------------------------------

-- Ingest positions, one row per feed: the stream's consumer offset ("feed" = the stream name) and
-- the delta poller's watermark ("feed" = 'wallet-delta'). Stored here, NOT in the broker, so that
-- projection state and consumer position advance or roll back in the same database transaction --
-- the effectively-once property described in spec section 6.2. A feed uses one of the two
-- columns; the other stays null.
create table "wqIngestCursor" (
    "feed"         text        not null,
    "streamOffset" bigint,
    "watermark"    timestamptz,
    "updatedAt"    timestamptz not null default now(),
    primary key ("feed")
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

-- ---------------------------------------------------------------------------------------------
-- Rollups
-- ---------------------------------------------------------------------------------------------

-- Sessions and plays are keyed by their business key and NOT partitioned: a transaction joining a
-- group finds its row by ("ownerType", "userId", "batchId"[, "subBatchId"]) alone, and a late, earlier
-- transaction only updates "startedAt" in place (a re-anchor) -- no row move.
--
-- Levels 1-2 page the owner's sessions (by "startedAt") merged with their standalone transactions
-- ("batchId" is null) -- there is no page-index table (plan D4). "visibleExclCancelled": the session
-- keeps at least one surviving (non-cancelled, non-zero, non-cancellation) transaction, which is
-- exactly "txCountExclCancelled" > 0, so it is generated from it and can never drift.
--
-- A session's (and a play's) product pair is its FIRST transaction's, first by
-- ("occurredAt", "sourceId", "source"). "startedAt" already is the first transaction's occurredAt;
-- "firstSourceId" and "firstSource" hold the rest of that transaction's identity, the tie-break for transactions
-- at the same instant, so the label does not depend on arrival order (plan D6, P-1).
-- Owner-keyed by ("ownerType", "userId"). "txCount" (and its ExclCancelled twin) count non-zero
-- rows only; "zeroTxCount" counts the group's zero-amount rows (plan D6). A zero-amount row never
-- creates, anchors or labels a group: a group exists once it has a non-zero row.
create table "wqPrjSession" (
    "ownerType"                smallint      not null,
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                text,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "zeroTxCount"              int           not null default 0,
    "playCount"                int           not null default 0,
    "playCountExclCancelled"   int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    "productType"              text,
    "firstSourceId"            text,
    "firstSource"              smallint,
    "visibleExclCancelled"     boolean       not null generated always as ("txCountExclCancelled" > 0) stored,
    primary key ("ownerType", "userId", "batchId")
);

create table "wqPrjPlay" (
    "ownerType"                smallint      not null,
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "subBatchId"               text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                text,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "zeroTxCount"              int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    "productType"              text,
    "firstSourceId"            text,
    "firstSource"              smallint,
    primary key ("ownerType", "userId", "batchId", "subBatchId")
);

-- Zero-amount rows waiting for their group (plan D6, final-review I1). A zero-amount row never creates
-- a group, so one that arrives before its session (or play) has a non-zero row is counted here, and
-- the group's first non-zero row takes the count into its "zeroTxCount" by primary key: creating a
-- group never searches the owner's history. "sessionCount": the batch's zero-amount rows that arrived
-- before its session existed (summed over the batch's rows); "playCount": this play's that arrived
-- before the play existed ("subBatchId" '' = no play). Each count is set to 0 when its group takes it,
-- and the row is deleted once both are 0 -- so any arrival order leaves the same rows. A row whose
-- group never gets a non-zero row stays, as the zero-amount rows themselves do.
create table "wqPrjPendingZero" (
    "ownerType"     smallint not null,
    "userId"        bigint   not null,
    "batchId"       text     not null,
    "subBatchId"    text     not null,
    "sessionCount"  int      not null default 0,
    "playCount"     int      not null default 0,
    check ("sessionCount" >= 0 and "playCount" >= 0 and ("subBatchId" <> '' or "playCount" = 0)),
    primary key ("ownerType", "userId", "batchId", "subBatchId")
);

-- ---------------------------------------------------------------------------------------------
-- Owner-month counts
-- ---------------------------------------------------------------------------------------------

-- One row per owner, UTC month and EXACT combination (plan D5): a total over [from, to) sums the
-- rows of its whole months and live-counts its two partial edge months. Not partitioned: a
-- multi-year owner holds a few rows per month. Mutable in place -- a cancel arriving today lowers a
-- count of a month long past (spec 5.7).
--
--   "unit"         0 = TX (one row per transaction: level 0), 1 = ENTRY (one per level-1/2 page entry:
--                  a session, or a standalone transaction -- "batchId" null, not a cancellation).
--   "source"       the transaction's source code; for a session ENTRY its first transaction's
--                  ("firstSource"): a session belongs to a read policy iff that source does. A read
--                  binds its policy's sources.
--   "productType"  the row's product type, '' = none. NEVER "all": every combination is stored
--                  exactly once, so a filtered total sums the rows its filter selects.
--   "type"         the transaction-type code at TX, '' at ENTRY (levels 1-2 filter no type).
--   "zeroAmount"   the counted rows are zero-amount stream transactions (plan D6): counted apart, read
--                  only by a policy that shows them. A session ENTRY is never zero-amount.
--
-- A session's ENTRY row sits at its current (month of "startedAt", "firstSource", "productType"): a
-- re-anchor or a relabel moves it. "cntExclCancelled" counts the visible ones (not cancelled; for a session,
-- "visibleExclCancelled"). Rows are deleted when "cnt" reaches 0 (sparse, arrival-order independent).
-- The months an owner has transactions in are its TX rows' months (TX rows are never deleted): the
-- cancel-target lookup reads them (plan D3).
create table "wqOwnerMonth" (
    "ownerType"         smallint not null,
    "userId"            bigint   not null,
    "month"             date     not null,  -- first day of the UTC month
    "source"            smallint not null,
    "unit"              smallint not null,
    "productType"       text     not null,
    "type"              text     not null,
    "zeroAmount"        boolean  not null,
    "cnt"               bigint   not null default 0,
    "cntExclCancelled"  bigint   not null default 0,
    check ("cnt" >= 0 and "cntExclCancelled" >= 0 and "cntExclCancelled" <= "cnt"),
    check ("unit" in (0, 1) and ("unit" = 0 or "type" = '')),
    check (extract(day from "month") = 1),
    primary key ("ownerType", "userId", "month", "source", "unit", "productType", "type", "zeroAmount")
);

-- ---------------------------------------------------------------------------------------------
-- Pending cancels
-- ---------------------------------------------------------------------------------------------

-- Cancels whose original has not yet arrived. A cancel can precede its target: different
-- transport, no global ordering (spec 6.3). Parking them here is what makes ingestion
-- order-independent.
--
-- A cancel targets a projected transaction of its OWN source (a TRANSACTION cancel cancels a TRANSACTION, a
-- PAYMENT cancel a PAYMENT), so the target is ("source", "targetSourceId") of the cancel's own owner.
-- sourceId alone is not unique across sources. The same-source assumption is to
-- be confirmed with the producer (O-13).
--
-- Every ProjectionTransaction field of the cancel is stored, so a drained cancel is rebuilt exactly as it
-- arrived (its real source included). The primary key makes re-parking the same cancel a no-op.
-- A cancel whose target never arrives is removed after a threshold (7 days by default) by the
-- pendingCancelResolution job (plan D3); the cancel's own "wqPrjTransaction" row stays.
create table "wqPrjPendingCancel" (
    "source"          smallint      not null,
    "cancelSourceId"  text          not null,
    "targetSourceId" text          not null,
    "ownerType"       smallint      not null,
    "userId"          bigint        not null,
    "occurredAt"      timestamptz   not null,
    "productId"       text,
    "batchId"         text,
    "subBatchId"      text,
    "type"            text          not null,
    "amount"          numeric(20,4) not null,
    "currency"        char(3)       not null,
    "balanceAfter"    numeric(20,4),
    "detail"          text,
    "parkedAt"        timestamptz   not null default now(),
    "productType"     text,
    primary key ("source", "cancelSourceId")
);

create index "wqPrjPendingCancel_target_idx" on "wqPrjPendingCancel" ("source", "targetSourceId");

-- ---------------------------------------------------------------------------------------------
-- Read-path indexes
-- ---------------------------------------------------------------------------------------------

-- Created on the partitioned parent, so Postgres propagates them to every partition (spec 7.7).
--
-- Level 0: key ("ownerType", "userId", "occurredAt" desc, "transactionId" desc) is the page's total
-- order; INCLUDE carries what the filters and the read policy check ("source", "productType", "type",
-- "cancelled", "zeroAmount"), so a key query is an index-only scan whatever the endpoint. "source" is
-- INCLUDE, not a key column: /cc reads every source in time order, and PG16 has no skip scan.
create index "wqPrjTransaction_cover" on "wqPrjTransaction" ("ownerType", "userId", "occurredAt" desc, "transactionId" desc) include ("source", "productType", "type", "cancelled", "zeroAmount");

-- Levels 1-2, standalone entries: the transactions with no session, cancellations excluded (a
-- cancellation is never a page entry). Key = the entry order (occurredAt desc, kind desc, refId desc)
-- with kind = "source" and refId = "sourceId" -- unique with "occurredAt" (the unique key) -- so it
-- merges with the session reading index below into one total order (plan D4).
create index "wqPrjTransaction_standalone" on "wqPrjTransaction" ("ownerType", "userId", "occurredAt" desc, "source" desc, "sourceId" desc) include ("productType", "cancelled", "zeroAmount") where "batchId" is null and "cancels" is null;

-- Level-1/2 assembly (spec 7.3): a page's sessions' transactions are fetched per session by batch id,
-- and a new group's zero-amount rows are counted the same way. Partial on session members.
create index "wqPrjTransaction_batch" on "wqPrjTransaction" ("ownerType", "userId", "batchId", "occurredAt") where "batchId" is not null;

-- Levels 1-2, sessions: kind 0, refId = "batchId"; INCLUDE what the product filter and
-- excludeCancelled read. The policy's "firstSource" check reads the heap row: sessions are updated by
-- nearly every transaction, so their pages are rarely all-visible and an index-only scan would visit
-- the heap anyway. Sessions and plays are otherwise found by primary key.
create index "wqPrjSession_reading" on "wqPrjSession" ("ownerType", "userId", "startedAt" desc, "batchId" desc) include ("productType", "visibleExclCancelled");

-- ---------------------------------------------------------------------------------------------
-- Quartz JobStore
-- ---------------------------------------------------------------------------------------------

-- Quartz owns this DDL and uses fixed QRTZ_* uppercase names, which cannot follow the camelCase
-- convention in spec section 3. A dedicated schema contains the exception (spec sections 3, 6.5,
-- 8.3). Kept last: "set local search_path" applies to the rest of this migration's transaction.
-- Keep public in the path too: flyway_schema_history lives in public and Flyway resolves it
-- unqualified against this same transaction's search_path.
--
-- Quartz's tables_postgres.sql (org.quartz.impl.jdbcjobstore.PostgreSQLDelegate).
create schema quartz;
set local search_path to quartz, public;

CREATE TABLE QRTZ_JOB_DETAILS
(
  SCHED_NAME        VARCHAR(120) NOT NULL,
  JOB_NAME          VARCHAR(200) NOT NULL,
  JOB_GROUP         VARCHAR(200) NOT NULL,
  DESCRIPTION       VARCHAR(250) NULL,
  JOB_CLASS_NAME    VARCHAR(250) NOT NULL,
  IS_DURABLE        BOOL         NOT NULL,
  IS_NONCONCURRENT  BOOL         NOT NULL,
  IS_UPDATE_DATA    BOOL         NOT NULL,
  REQUESTS_RECOVERY BOOL         NOT NULL,
  JOB_DATA          BYTEA        NULL,
  PRIMARY KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE QRTZ_TRIGGERS
(
  SCHED_NAME     VARCHAR(120) NOT NULL,
  TRIGGER_NAME   VARCHAR(200) NOT NULL,
  TRIGGER_GROUP  VARCHAR(200) NOT NULL,
  JOB_NAME       VARCHAR(200) NOT NULL,
  JOB_GROUP      VARCHAR(200) NOT NULL,
  DESCRIPTION    VARCHAR(250) NULL,
  NEXT_FIRE_TIME BIGINT       NULL,
  PREV_FIRE_TIME BIGINT       NULL,
  PRIORITY       INTEGER      NULL,
  TRIGGER_STATE  VARCHAR(16)  NOT NULL,
  TRIGGER_TYPE   VARCHAR(8)   NOT NULL,
  START_TIME     BIGINT       NOT NULL,
  END_TIME       BIGINT       NULL,
  CALENDAR_NAME  VARCHAR(200) NULL,
  MISFIRE_INSTR  SMALLINT     NULL,
  JOB_DATA       BYTEA        NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
  REFERENCES QRTZ_JOB_DETAILS (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE QRTZ_SIMPLE_TRIGGERS
(
  SCHED_NAME      VARCHAR(120) NOT NULL,
  TRIGGER_NAME    VARCHAR(200) NOT NULL,
  TRIGGER_GROUP   VARCHAR(200) NOT NULL,
  REPEAT_COUNT    BIGINT       NOT NULL,
  REPEAT_INTERVAL BIGINT       NOT NULL,
  TIMES_TRIGGERED BIGINT       NOT NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
  REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_CRON_TRIGGERS
(
  SCHED_NAME      VARCHAR(120) NOT NULL,
  TRIGGER_NAME    VARCHAR(200) NOT NULL,
  TRIGGER_GROUP   VARCHAR(200) NOT NULL,
  CRON_EXPRESSION VARCHAR(120) NOT NULL,
  TIME_ZONE_ID    VARCHAR(80),
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
  REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_SIMPROP_TRIGGERS
(
  SCHED_NAME    VARCHAR(120)   NOT NULL,
  TRIGGER_NAME  VARCHAR(200)   NOT NULL,
  TRIGGER_GROUP VARCHAR(200)   NOT NULL,
  STR_PROP_1    VARCHAR(512)   NULL,
  STR_PROP_2    VARCHAR(512)   NULL,
  STR_PROP_3    VARCHAR(512)   NULL,
  INT_PROP_1    INT            NULL,
  INT_PROP_2    INT            NULL,
  LONG_PROP_1   BIGINT         NULL,
  LONG_PROP_2   BIGINT         NULL,
  DEC_PROP_1    NUMERIC(13, 4) NULL,
  DEC_PROP_2    NUMERIC(13, 4) NULL,
  BOOL_PROP_1   BOOL           NULL,
  BOOL_PROP_2   BOOL           NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
  REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_BLOB_TRIGGERS
(
  SCHED_NAME    VARCHAR(120) NOT NULL,
  TRIGGER_NAME  VARCHAR(200) NOT NULL,
  TRIGGER_GROUP VARCHAR(200) NOT NULL,
  BLOB_DATA     BYTEA        NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
  REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_CALENDARS
(
  SCHED_NAME    VARCHAR(120) NOT NULL,
  CALENDAR_NAME VARCHAR(200) NOT NULL,
  CALENDAR      BYTEA        NOT NULL,
  PRIMARY KEY (SCHED_NAME, CALENDAR_NAME)
);

CREATE TABLE QRTZ_PAUSED_TRIGGER_GRPS
(
  SCHED_NAME    VARCHAR(120) NOT NULL,
  TRIGGER_GROUP VARCHAR(200) NOT NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_FIRED_TRIGGERS
(
  SCHED_NAME        VARCHAR(120) NOT NULL,
  ENTRY_ID          VARCHAR(95)  NOT NULL,
  TRIGGER_NAME      VARCHAR(200) NOT NULL,
  TRIGGER_GROUP     VARCHAR(200) NOT NULL,
  INSTANCE_NAME     VARCHAR(200) NOT NULL,
  FIRED_TIME        BIGINT       NOT NULL,
  SCHED_TIME        BIGINT       NOT NULL,
  PRIORITY          INTEGER      NOT NULL,
  STATE             VARCHAR(16)  NOT NULL,
  JOB_NAME          VARCHAR(200) NULL,
  JOB_GROUP         VARCHAR(200) NULL,
  IS_NONCONCURRENT  BOOL         NULL,
  REQUESTS_RECOVERY BOOL         NULL,
  PRIMARY KEY (SCHED_NAME, ENTRY_ID)
);

CREATE TABLE QRTZ_SCHEDULER_STATE
(
  SCHED_NAME        VARCHAR(120) NOT NULL,
  INSTANCE_NAME     VARCHAR(200) NOT NULL,
  LAST_CHECKIN_TIME BIGINT       NOT NULL,
  CHECKIN_INTERVAL  BIGINT       NOT NULL,
  PRIMARY KEY (SCHED_NAME, INSTANCE_NAME)
);

CREATE TABLE QRTZ_LOCKS
(
  SCHED_NAME VARCHAR(120) NOT NULL,
  LOCK_NAME  VARCHAR(40)  NOT NULL,
  PRIMARY KEY (SCHED_NAME, LOCK_NAME)
);

CREATE INDEX IDX_QRTZ_J_REQ_RECOVERY
  ON QRTZ_JOB_DETAILS (SCHED_NAME, REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_J_GRP
  ON QRTZ_JOB_DETAILS (SCHED_NAME, JOB_GROUP);

CREATE INDEX IDX_QRTZ_T_J
  ON QRTZ_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_JG
  ON QRTZ_TRIGGERS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_C
  ON QRTZ_TRIGGERS (SCHED_NAME, CALENDAR_NAME);
CREATE INDEX IDX_QRTZ_T_G
  ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_T_STATE
  ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_STATE
  ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_G_STATE
  ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NEXT_FIRE_TIME
  ON QRTZ_TRIGGERS (SCHED_NAME, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST
  ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_MISFIRE
  ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE
  ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE_GRP
  ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_GROUP, TRIGGER_STATE);

CREATE INDEX IDX_QRTZ_FT_TRIG_INST_NAME
  ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME);
CREATE INDEX IDX_QRTZ_FT_INST_JOB_REQ_RCVRY
  ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME, REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_FT_J_G
  ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_JG
  ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_T_G
  ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_FT_TG
  ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_GROUP);
