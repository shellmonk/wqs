-- WQS baseline schema. The service is not live: this file is edited in place until go-live;
-- changes after go-live start at V2.

-- ---------------------------------------------------------------------------------------------
-- Transactions
-- ---------------------------------------------------------------------------------------------

-- Every projected row, from every source, stored once. Partitioned by month on "occurredAt";
-- partitions are created on demand by the writer (wqs.db.Partitions). The owner is
-- ("ownerType", "userId"): a player 1717 and a retailer 1717 never share rows.
-- "source": 1 TRANSACTION (stream), 2 MANUAL_ADJUSTMENT, 3 CLAIMABLE_PRIZE, 4 PAYMENT, 5 LIMIT.
-- "transactionId" is the row's synthetic id, from "wqTransactionIdSeq". Postgres requires every key
-- of a partitioned table to include the partition column, so it cannot be the primary key alone;
-- the sequence keeps it unique. The primary key doubles as the index pages read by: an owner
-- query also filters "ownerType", checked on the row (owners sharing a userId are rare).
-- The unique key catches a redelivered row.
create table "wqPrjTransaction" (
    "ownerType"            smallint      not null,
    "userId"               bigint        not null,
    "occurredAt"           timestamptz   not null,
    "transactionId"        bigint        not null,
    "source"               smallint      not null,
    "sourceId"             text          not null,
    "type"                 text          not null,
    "amount"               numeric(20,4) not null,
    "currency"             char(3)       not null,
    "balanceAfter"         numeric(20,4),
    "productId"            text,
    "productType"          text,
    "batchId"              text,
    "subBatchId"           text,
    "transactionReference" text,
    "cancelled"            boolean       not null default false,
    "detail"               jsonb         not null default '{}',
    "ingestedAt"           timestamptz   not null default now(),
    -- A zero-amount stream transaction: stored, never shown, not counted in session or play totals.
    "zeroAmount"           boolean       not null generated always as ("amount" = 0 and "source" = 1) stored,
    primary key ("userId", "occurredAt", "transactionId"),
    constraint "wqPrjTransaction_sourceKey" unique ("source", "sourceId", "occurredAt")
) partition by range ("occurredAt");

create sequence "wqTransactionIdSeq" as bigint;

-- Levels 1-2 page through sessions merged with the rows that have no session; this index serves
-- the second half. A session's own rows are found by batch, bounded by the session's time span.
create index "wqPrjTransaction_unsessioned" on "wqPrjTransaction" ("userId", "occurredAt" desc, "transactionId" desc)
    where "batchId" is null;
create index "wqPrjTransaction_session" on "wqPrjTransaction" ("userId", "batchId", "occurredAt")
    where "batchId" is not null;

-- ---------------------------------------------------------------------------------------------
-- Sessions and plays
-- ---------------------------------------------------------------------------------------------

-- Totals per session (a batch id) and per play (a sub-batch id), updated in the same transaction
-- as each row that joins them, so pages read finished numbers. Not partitioned: found by key.
-- "totalPlays" and "totalWins" are absolute amounts; "net" = "totalWins" - "totalPlays".
-- Each total is kept twice: over all rows, and over rows not cancelled ("...ExclCancelled").
-- Zero-amount stream rows add nothing. A session has one currency: a row in another currency is
-- dead-lettered. "productId"/"productType" are those of the earliest row.
create table "wqPrjSession" (
    "sessionId"                bigint        generated always as identity primary key,
    "ownerType"                smallint      not null,
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                text,
    "productType"              text,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "playCount"                int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "playCountExclCancelled"   int           not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    constraint "wqPrjSession_batchKey" unique ("ownerType", "userId", "batchId")
);

create index "wqPrjSession_byStart" on "wqPrjSession" ("userId", "startedAt" desc, "sessionId" desc);

create table "wqPrjPlay" (
    "playId"                   bigint        generated always as identity primary key,
    "sessionId"                bigint        not null references "wqPrjSession" ("sessionId"),
    "ownerType"                smallint      not null,
    "userId"                   bigint        not null,
    "batchId"                  text          not null,
    "subBatchId"               text          not null,
    "startedAt"                timestamptz   not null,
    "endedAt"                  timestamptz   not null,
    "productId"                text,
    "productType"              text,
    "currency"                 char(3)       not null,
    "txCount"                  int           not null default 0,
    "totalPlays"               numeric(20,4) not null default 0,
    "totalWins"                numeric(20,4) not null default 0,
    "net"                      numeric(20,4) not null default 0,
    "txCountExclCancelled"     int           not null default 0,
    "totalPlaysExclCancelled"  numeric(20,4) not null default 0,
    "totalWinsExclCancelled"   numeric(20,4) not null default 0,
    "netExclCancelled"         numeric(20,4) not null default 0,
    constraint "wqPrjPlay_subBatchKey" unique ("ownerType", "userId", "batchId", "subBatchId")
);

create index "wqPrjPlay_session" on "wqPrjPlay" ("sessionId");

-- ---------------------------------------------------------------------------------------------
-- Ingest state
-- ---------------------------------------------------------------------------------------------

-- One row per feed: the stream's last processed offset ("feed" = the stream name). Kept here,
-- not in the broker, so a row and the offset that produced it commit or roll back together.
create table "wqIngestCursor" (
    "cursorId"     bigint      generated always as identity primary key,
    "feed"         text        not null,
    "streamOffset" bigint,
    "updatedAt"    timestamptz not null default now(),
    constraint "wqIngestCursor_feedKey" unique ("feed")
);

-- Input that could not be projected. Never the raw message: wallet messages can carry card data
-- (e.g. "toDescription"). A message that could not be read is identified by its offset and the
-- SHA-256 of its body (the message itself stays in the stream); a readable but rejected one keeps
-- only its decoded, allowlisted fields in "payload".
create table "wqDeadLetter" (
    "deadLetterId" bigint      generated always as identity primary key,
    "feed"         text        not null,
    "streamOffset" bigint,
    "sourceId"     text,
    "reason"       text        not null,
    "error"        text        not null,
    "bodySha256"   text,
    "payload"      jsonb,
    "failedAt"     timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------
-- Quartz JobStore (Quartz's tables_postgres.sql, for PostgreSQLDelegate)
-- ---------------------------------------------------------------------------------------------
-- Quartz uses fixed upper-case QRTZ_* names, which cannot follow the camelCase convention, so
-- they live in their own schema. Keep this section LAST: "set local search_path" applies to the
-- rest of the migration's transaction. public stays on the path because flyway_schema_history
-- lives there and Flyway resolves it unqualified.
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
