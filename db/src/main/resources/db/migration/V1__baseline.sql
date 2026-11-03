-- WQS baseline schema. Every camelCase identifier is double-quoted (spec 3).
--
-- Sentinels used across the schema: "productType" = '' is ALL product types (unreachable from
-- input: every blank is normalised to null at the domain boundary); "type" = '' is ALL
-- transaction types (a stored code is never blank). Products are strings on the wire
-- ("productId" "TIKIKALACABANA", "productType" "instants"); the eInstant view is selected by
-- product type (wqs.einstant.product-types).

-- ---------------------------------------------------------------------------------------------
-- Locators
-- ---------------------------------------------------------------------------------------------

-- Global sourceId -> partition locator. Deliberately NOT partitioned: a cancel event references an
-- original by sourceId with no timestamp, so resolving it against a partitioned index would probe
-- every monthly partition (spec 5.4). Doubles as the idempotency gate for ingestion: an
-- ON CONFLICT DO NOTHING insert that conflicts means the event is already applied.
--
-- "zeroAmount": a zero-amount transaction is recorded in the locator ONLY -- so a re-delivery is a
-- duplicate and a cancel referencing it resolves -- and in no transaction table, rollup, page index or
-- counter (plan D8). "cancels" is the target of such a locator-only CANCEL: it has no transaction row to
-- carry it, and the reconciliation oracle must still see that the target is cancelled. Projected
-- rows leave it null; their transaction rows hold it.
--
-- "versionAt": the stored version of a polled row, its source "updated" ("statusDate" for
-- adjustment requests). A delta row is applied only when strictly newer, a duplicate when equal,
-- stale when older. Null for stream transactions, which are immutable and never versioned.
create table "wqPrjTransactionLocator" (
    "source"     smallint    not null,
    "sourceId"   text        not null,
    "userId"     bigint      not null,
    "occurredAt" timestamptz not null,
    "transactionId"     bigint      not null,
    "zeroAmount" boolean     not null default false,
    "cancels"    text,
    "versionAt"  timestamptz,
    primary key ("source", "sourceId"),
    constraint "wqPrjTransactionLocator_cancels_locator_only" check ("cancels" is null or "zeroAmount")
);

create index "wqPrjTransactionLocator_user_idx" on "wqPrjTransactionLocator" ("userId");

-- Locates a session or play by its business key, so a later transaction joining an existing group
-- can find the row whose primary key includes the partition key. Same pattern and same reason as
-- "wqPrjTransactionLocator" (spec 5.4). "subBatchId" = '' identifies the session itself.
create table "wqPrjGroupLocator" (
    "userId"     bigint      not null,
    "batchId"    text        not null,
    "subBatchId" text        not null default '',
    "startedAt"  timestamptz not null,
    primary key ("userId", "batchId", "subBatchId")
);

-- ---------------------------------------------------------------------------------------------
-- Level-0 transactions
-- ---------------------------------------------------------------------------------------------

-- Level-0 rows for the user-facing views: transactions and claimable prizes (spec 4).
-- "type" holds the transaction-type code as it arrived (wqs.domain.TransactionType, plan D1); a
-- code the enum does not list is stored as-is and rolls up as NEUTRAL.
create table "wqPrjWebTransaction" (
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
    primary key ("userId", "occurredAt", "transactionId")
) partition by range ("occurredAt");

-- Level-0 rows for the operator unified view: all five sources (spec 4).
create table "wqPrjCcTransaction" (
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
    primary key ("userId", "occurredAt", "transactionId")
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

-- A session's (and a play's) product pair is its FIRST transaction's, first by
-- ("occurredAt", "sourceId", "source"). "startedAt" already is the first transaction's occurredAt;
-- "firstSourceId" and "firstSource" hold the rest of that transaction's identity, the tie-break for transactions
-- at the same instant, so the label does not depend on arrival order (plan D6, P-1).
create table "wqPrjSession" (
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                text,
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
    "productType"              text,
    "firstSourceId"            text,
    "firstSource"              smallint,
    primary key ("userId", "startedAt", "batchId")
) partition by range ("startedAt");

create table "wqPrjPlay" (
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "subBatchId"               text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                text,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    "productType"              text,
    "firstSourceId"            text,
    "firstSource"              smallint,
    primary key ("userId", "startedAt", "batchId", "subBatchId")
) partition by range ("startedAt");

-- ---------------------------------------------------------------------------------------------
-- Level-1/2 page index
-- ---------------------------------------------------------------------------------------------

-- One narrow row per session and per unsessioned transaction, merged at WRITE time so paging stays a
-- single covering index scan (spec 5.6). A page index, not a payload store: it carries only what
-- ordering, filtering and counting need. Payload is fetched by "refId" during assembly.
create table "wqPrjWebHighLevel" (
    "userId"               bigint      not null,
    "occurredAt"           timestamptz not null,  -- session "startedAt", or the transaction's "occurredAt"
    "kind"                 smallint    not null,  -- 0 = SESSION, otherwise the source code
    "refId"                text        not null,  -- "batchId" for a session, "sourceId" for a transaction
    "productId"            text,
    "currency"             char(3)     not null,
    "visibleExclCancelled" boolean     not null default true,
    "productType"          text,
    primary key ("userId", "occurredAt", "kind", "refId")
) partition by range ("occurredAt");

create table "wqPrjCcHighLevel" (
    "userId"               bigint      not null,
    "occurredAt"           timestamptz not null,
    "kind"                 smallint    not null,
    "refId"                text        not null,
    "productId"            text,
    "currency"             char(3)     not null,
    "visibleExclCancelled" boolean     not null default true,
    "productType"          text,
    primary key ("userId", "occurredAt", "kind", "refId")
) partition by range ("occurredAt");

-- ---------------------------------------------------------------------------------------------
-- Counters
-- ---------------------------------------------------------------------------------------------

-- Tier 1: whole months, keyed on (productType, type). Periods are UTC (spec 7.1). Rows are
-- sparse -- they exist only for periods where the user had activity. Counters are mutable in
-- place: a cancel arriving today can decrement a counter for a transaction from eight months ago,
-- which rules out append-only storage (spec 5.7).
create table "wqPrjCountMonth" (
    "userId"            bigint   not null,
    "viewKind"          smallint not null,  -- 1 = WEB, 2 = CC
    "pageUnit"          smallint not null,  -- 0 = TRANSACTION (L0), 1 = HIGH_LEVEL (L1/L2)
    "productType"       text     not null,  -- '' = ALL product types
    "type"              text     not null,  -- '' = ALL transaction types
    "period"            date     not null,  -- first day of the month, UTC
    "cntAll"            bigint   not null default 0,
    "cntExclCancelled"  bigint   not null default 0,
    check ("cntAll" >= 0 and "cntExclCancelled" >= 0 and "cntExclCancelled" <= "cntAll"),
    primary key ("userId", "viewKind", "pageUnit", "productType", "type", "period")
);

-- Tier 2: whole days, for the partial months at each end of a requested range. Monthly
-- RANGE-partitioned on "period"; PartitionManager provisions the partitions.
create table "wqPrjCountDay" (
    "userId"            bigint   not null,
    "viewKind"          smallint not null,
    "pageUnit"          smallint not null,
    "productType"       text     not null,
    "type"              text     not null,
    "period"            date     not null,
    "cntAll"            bigint   not null default 0,
    "cntExclCancelled"  bigint   not null default 0,
    check ("cntAll" >= 0 and "cntExclCancelled" >= 0 and "cntExclCancelled" <= "cntAll"),
    primary key ("userId", "viewKind", "pageUnit", "productType", "type", "period")
) partition by range ("period");

-- ---------------------------------------------------------------------------------------------
-- Pending cancels
-- ---------------------------------------------------------------------------------------------

-- Cancels whose original has not yet arrived. A cancel can precede its target: different
-- transport, no global ordering (spec 6.3). Parking them here is what makes ingestion
-- order-independent.
--
-- A cancel targets a projected transaction of its OWN source (a TRANSACTION cancel cancels a TRANSACTION, a
-- PAYMENT cancel a PAYMENT), so the target is ("source", "cancelsSourceId") -- the same key as
-- "wqPrjTransactionLocator". sourceId alone is not unique across sources. The same-source assumption is to
-- be confirmed with the producer (O-13).
--
-- Every ProjectionTransaction field of the cancel is stored, so a drained cancel is rebuilt exactly as it
-- arrived (its real source included). The primary key makes re-parking the same cancel a no-op.
create table "wqPrjPendingCancel" (
    "source"          smallint      not null,
    "cancelSourceId"  text          not null,
    "cancelsSourceId" text          not null,
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

create index "wqPrjPendingCancel_target_idx" on "wqPrjPendingCancel" ("source", "cancelsSourceId");

-- ---------------------------------------------------------------------------------------------
-- Read-path indexes
-- ---------------------------------------------------------------------------------------------

-- Covering indexes for the read paths (spec 7.7), created on the partitioned parents so Postgres
-- propagates them to every partition. Level 0 filters by "productType" and "type", levels 1/2 by
-- "productType" only (transaction types filter only the nested transactions, never the page
-- index; spec 4.1). On the page index "kind" and "refId" are key columns, not INCLUDE, so it has a
-- total order ("occurredAt" desc, "kind" desc, "refId" desc) for level 1/2 paging, like "transactionId"
-- on the transactions.
create index "wqPrjWebTransaction_cover" on "wqPrjWebTransaction" ("userId", "occurredAt" desc, "transactionId" desc) include ("cancelled", "productType", "type");
create index "wqPrjCcTransaction_cover"  on "wqPrjCcTransaction"  ("userId", "occurredAt" desc, "transactionId" desc) include ("cancelled", "productType", "type");
create index "wqPrjWebHighLevel_cover" on "wqPrjWebHighLevel" ("userId", "occurredAt" desc, "kind" desc, "refId" desc) include ("productType", "visibleExclCancelled");
create index "wqPrjCcHighLevel_cover"  on "wqPrjCcHighLevel"  ("userId", "occurredAt" desc, "kind" desc, "refId" desc) include ("productType", "visibleExclCancelled");

-- Level-1/2 assembly (spec 7.3 "each bounded by the page size"): a page's sessions' transactions and
-- plays are fetched per session by batch id. Without these the only usable index is
-- ("userId", "occurredAt"/"startedAt"), so a long-lived session made the fetch read every row of
-- the user inside its window. Partial on sessioned transactions: unsessioned rows never probe it.
create index "wqPrjWebTransaction_batch" on "wqPrjWebTransaction" ("userId", "batchId", "occurredAt") where "batchId" is not null;
create index "wqPrjCcTransaction_batch"  on "wqPrjCcTransaction"  ("userId", "batchId", "occurredAt") where "batchId" is not null;
create index "wqPrjPlay_batch"    on "wqPrjPlay"    ("userId", "batchId", "startedAt");

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
