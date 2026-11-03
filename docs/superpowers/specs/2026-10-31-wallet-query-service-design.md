# Wallet Query Service (WQS) — Design

**Date:** 2026-10-31
**Status:** Approved design, pending implementation plan

---

## 1. Purpose

WQS is the read side of a CQRS split. The wallet service owns transaction writes; WQS owns
transaction-history queries. The wallet's main transaction table has grown past 150 million rows,
and serving history from it competes with the write path. WQS maintains independent read
projections so history queries never touch wallet's write tables.

WQS serves two consumers over two endpoints, with three logical views and three aggregation levels.

### Non-goals

- WQS never writes to wallet-owned data. It is read-only with respect to the source system.
- WQS is not a reporting or analytics warehouse. Every query is scoped to a single user.
- WQS does not own authentication. It consumes an already-established principal.

---

## 2. Technology

| Concern | Choice |
|---|---|
| Language | Java 21 (virtual threads enabled) |
| Framework | Spring Boot 3.x, MVC (not WebFlux) |
| Build | Gradle, two subprojects |
| SQL | jOOQ |
| Migrations | Flyway |
| Property testing | Jqwik |
| Architecture testing | ArchUnit |
| Integration testing | Testcontainers (Postgres + RabbitMQ) |
| Database | AWS Aurora PostgreSQL |
| Messaging | RabbitMQ Streams |
| Scheduling | Quartz, clustered JDBC JobStore |
| Mapping | MapStruct |
| Boilerplate | Lombok |
| Metrics | Micrometer → Prometheus / Amazon Managed Prometheus |
| Tracing | OpenTelemetry → collector |

**Virtual threads over WebFlux.** jOOQ is blocking JDBC. Virtual threads
(`spring.threads.virtual.enabled=true`) deliver the concurrency benefit without WebFlux's
pervasive reactive types. R2DBC is explicitly rejected: it would force either a reactive jOOQ
dialect or a split persistence stack, for no benefit at this read volume.

### 2.1 Why jOOQ (decision reviewed 2026-11-01)

The choice was challenged and reaffirmed. The reasons, strongest first:

1. **Index-only scans stay enforceable.** §7.7 stakes read performance on a single covering index
   with `INCLUDE` columns, which only works if queries select exactly the covered columns.
   Selecting one extra column silently degrades to heap fetches — no error, no test failure. jOOQ
   makes the projection list explicit at the call site, so the invariant is visible and reviewable.
   An ORM hydrating entities selects whatever the entity declares, making a core performance
   invariant invisible and breakable by an unrelated field addition.
2. **One source of schema truth.** Generating from Flyway migrations (§8.3) means a schema change
   that breaks a query fails the **build**, not a rarely-hit runtime branch. There are no entities
   to drift and no query strings to drift.
3. **Dynamic composition is the dominant query shape.** The read path is a filter matrix across
   three aggregation levels and two view tables, and it will grow (O-6). Composing that with
   strings is where both injection and malformed-SQL defects originate; composing it with a typed
   DSL is checked.
4. **camelCase quoting is handled structurally** (§3) rather than by per-query discipline.
5. **No ORM semantics are needed.** WQS persists no mutable object graph — it projects immutable
   events into denormalized tables and reads them back flat. Hibernate's dirty checking, L1 cache,
   lazy loading and cascades are overhead with no corresponding benefit, and `@Entity` mapping
   fights partitioned tables and composite keys for nothing.

**Organizational context.** The team's existing codebase uses Spring Data JPA alongside largely
raw SQL, which has produced two unchecked descriptions of the same schema. WQS is a deliberate
pilot of a single-model alternative: self-contained, no legacy entities, a twelve-table schema, and
performance-critical enough that the benefits are observable. jOOQ coexists with JPA, so no other
service is forced to migrate.

**Accepted risk (R-6).** An unfamiliar data-access library is being introduced on the service with
the strictest performance requirements. Mitigated by build-time codegen catching schema drift and
by properties P-2, P-5 and P-6 catching logic errors against oracles.

**Serious alternative considered.** `JdbcClient` with handwritten SQL was the only real contender —
simpler build, no Docker dependency, full control. Rejected because the query set is neither small
nor static, and because it would reproduce the unchecked-string problem the pilot exists to solve.
Spring Data JPA, MyBatis, QueryDSL and R2DBC were each rejected on the grounds above.

### 2.2 jOOQ usage rules

These exist because jOOQ codebases have their own failure modes, all of which recreate the problem
it was adopted to solve:

1. **`DSL.field(String)` and `.sql(...)` are prohibited** without an explicit, commented
   justification. They reintroduce unchecked strings *inside* jOOQ while appearing safe. Always use
   generated table and field constants. Enforced by review, and by an ArchUnit rule where
   practical.
2. **jOOQ types stay inside the query layer** (`read/`, `projection/`). Neither `DSLContext` nor
   generated types may appear in `api/` or `domain/`.
3. **Generated `Record` types are never returned upward.** Map to domain types or DTOs at the
   query-layer boundary, or the API contract becomes coupled to table shape — forfeiting the
   freedom to reshape tables that this design depends on.
4. **One query class per (view, aggregation level)**, not a single builder parameterized by
   booleans. The combinatorial filter space is the most likely thing to rot into unreadable
   conditionals.

Rules 2–4 concern layering rather than jOOQ specifically, and would apply to any data-access
choice. All four are enforced by ArchUnit (§8.5) rather than by review alone.

### 2.3 Mapping layer (MapStruct)

Mappings exist at two boundaries: source payload → item on the write side, and jOOQ `Record` →
domain → response DTO on the read side. Both use **MapStruct**, generating mappers at compile
time.

The reason it fits here is the same reason jOOQ does: **an unmapped field fails the build.** Adding
a column breaks compilation twice — once at the query, once at the mapper — so schema knowledge
stays checked rather than assumed.

Rules:

1. **Mappers live in the query layer** (`read/`, `projection/`), never in `api/`. A mapper
   necessarily touches both jOOQ types and DTOs, so placing one in `api/` would violate §2.2 rule
   2 and fail the ArchUnit check. The mapper *is* the boundary; it belongs on the inside of it.
2. **Unmapped target properties are an error, not a warning**
   (`unmappedTargetPolicy = ReportingPolicy.ERROR`). Downgrading this discards the entire benefit.
3. **`"detail" jsonb` needs explicit hand-written mapping.** MapStruct cannot infer a mapping for
   an open-ended JSON blob, so each source's `detail` projection is a named method.

### 2.4 Lombok

Lombok is used where it removes real boilerplate, with Java 21 records preferred where they fit:

- **Prefer records** for immutable DTOs, domain value types and API response types. A record is
  clearer than `@Value` and needs no annotation processing.
- **Use Lombok** for `@Slf4j`, `@RequiredArgsConstructor` on Spring components with several
  dependencies, and `@Builder` on objects with many optional fields.
- **Avoid** `@Data` and mutable `@EqualsAndHashCode`. There are no JPA entities here, so the usual
  justification does not arise, and mutable equality on a projection type invites subtle bugs.

**Build interaction — Lombok and MapStruct collide unless ordered explicitly.** Both are annotation
processors, and MapStruct must run *after* Lombok has generated accessors or it will not see them,
producing mappers with silently missing fields. The `annotationProcessor` configuration must list,
in order: `lombok`, `lombok-mapstruct-binding`, `mapstruct-processor`. Omitting the binding
artifact is the classic failure, and its symptom is a compiling mapper that drops properties rather
than a build error.

The full clean-build chain is therefore: **Testcontainers → Flyway → jOOQ codegen → Lombok →
MapStruct → compile.** A failure can surface several steps downstream of its cause.

---

## 3. Naming convention

Database identifiers — **both tables and columns** — are camelCase and therefore must be
double-quoted in all DDL and hand-written SQL, because Postgres folds unquoted identifiers to
lowercase.

The one exception is Quartz, which owns its DDL and uses fixed `QRTZ_*` uppercase names. Those
tables live in a separate `quartz` schema (§6.5) so the exception stays contained.

```sql
create table "wqWebItem" ( "userId" bigint not null, "occurredAt" timestamptz not null, ... );
```

jOOQ quotes identifiers by default, so **quoting** needs no special handling in generated classes
or jOOQ-built queries. Flyway migrations are raw SQL and are the only surface where a missing
quote pair can ship silently.

Identifier **naming** in generated code does need configuration, however: the default generator
strategy would flatten these camel humps. See §8.3.

**Mitigation:** a build-time check fails the build if any migration file contains an unquoted
`wq[A-Z]` identifier.

---

## 4. Data sources

Five sources feed WQS.

| # | Source | Transport | Feeds |
|---|---|---|---|
| 1 | Transactions | RabbitMQ stream (push) | `/web` and `/cc` |
| 2 | Manual adjustments | `/wallet/delta` (poll) | `/cc` only |
| 3 | Claimable prizes | `/wallet/delta` (poll) | `/web` and `/cc` |
| 4 | Payments | `/wallet/delta` (poll) | `/cc` only |
| 5 | Limits | `/wallet/delta` (poll) | `/cc` only |

**Transactions** (`walAccountTransactions`, via the RabbitMQ stream) are **immutable**: a row is
written once and never updated. Cancellation arrives as a **new transaction referencing the
original** (`transactionReference`), which makes the projection mutable even though the source is
not.

**The four polled sources are mutable** (`walManualAdjustmentRequests`, `walClaimablePrizes`,
`walPayments`, `walLimits`). Their rows have lifecycles — payments move PENDING → APPROVED →
VERIFIED / CANCELLED / REJECTED / EXPIRED, claims UNCLAIMED → CLAIMED → COMPLETED / REJECTED /
EXPIRED, adjustment requests PENDING → APPROVED / REJECTED, limits are requested then approved —
and `/wallet/delta` returns the **current version** of every row changed since the cursor. A
periodic Quartz job per source (§6.5) polls it and applies each row as an **upsert of a newer
version**, not an insert. Consequences:

- The idempotency gate cannot be "seen this sourceId → drop": a re-read of the same version (the
  overlap window) must be a no-op, but a newer version must replace the stored one. The row's own
  change timestamp (`updated` / `statusDate`) is its version: apply when strictly newer, treat as
  duplicate when equal, ignore (and count) when older.
- Any field a counter, a rollup or `"visibleExclCancelled"` depends on can change on an update.
  Applying a new version therefore retracts the old version's contributions and adds the new
  one's, in the same transaction — the same mechanism cancellation already uses.
- `"occurredAt"` is the source's `created`, which never changes, so a new version never moves
  partitions.

**Grouping.** Sessions are keyed by `batchId` and plays by `subBatchId` (§5.5). The source column
`aggregationId` is **obsolete** and is not used.

### 4.1 Source contract decisions (2026-11-02)

References: `docs/reference/api-contract.md` (API as provided), `docs/reference/source-schema.sql`
(wallet tables), `docs/reference/stream-message-samples.md` (real stream messages — golden
fixtures for the decoder). These supersede the O-13 wire-format assumption phases 2–4 were built on.

**Stream message → item** (`walAccountTransactions`):

| Item field | Wire source | Rule |
|---|---|---|
| `sourceId` | `id` | string |
| `userId` | `userContext.ownerId` = `"{userType}-{userId}"` | only `userType` 1 (players) is projected; other owners are skipped and counted |
| `occurredAt` | `createdContext.created` | ISO-8601 UTC (`Z`); `issued` goes to detail |
| type | `type` (string, e.g. `TT_BUY`) | mapped to the stored `smallint` via a data-driven code table |
| `productId` / `productType` | `productId`, `productType` | strings; `""` or absent → null |
| `amount`, `currency` | `amount.amount`, `amount.currency` | |
| `balanceAfter` | `totalPostBalance` | |
| `batchId` / `subBatchId` | `batchContext.*` | `""` → null; `batchEnded` kept |
| `cancels` | `references.transactionReference` | **only** when `type` ∈ {`TT_CANCEL`, `CANCEL`, `TT_TOMBSTONE`} and the reference ≠ `id` — the producer self-references when there is no link. `*_UNDO` / `*_ROLLBACK` are ordinary items |

Every empty string is normalised to null. The non-player check runs after the message is decoded,
so a malformed non-player message is dead-lettered (raw body included) rather than skipped. All accounts (`AT_MAIN`, bonus, …) and non-monetary
rows are projected. Remaining fields go to `"detail"` through an **allowlist**, never "the rest of
the row" — `walPayments` carries encrypted card and bank data.

**Zero-amount items are not shown.** A transaction with `amount` = 0 (e.g. a losing `PRIZE` play
step) is recorded in the locator — so it stays idempotent and a cancel referencing it resolves —
but is written to no view, counter or rollup.

**API** (replaces the phase-4 shapes):

- Paths `GET /web/wallet-data/rest/v1/projection` and
  `GET /cc/wallet-data/rest/v1/projection/{playerId}`.
- Parameters `from`, `to`, `productTypes` (list), `transactionTypes` (list),
  `excludeCancelledTransactions` (default false), `aggregationLevel` 0–2, `pageNumber`
  (**1-based**), `pageSize` (top-level items).
- Envelope `{pageNumber, pagesAvailable, pageSize, totalRowCount, data}`. Level 1 nests a
  session's transactions under `txs` (the `subitems` object in the example is a typo); level 2
  nests plays under `subitems`, each with `txs`. Amounts are JSON numbers. Session `amount` =
  `totalWin` − `totalPlay`. Unsessioned items appear in `data` as plain transaction objects,
  interleaved by time.
- `productTypes` matches the stored string exactly. `transactionTypes` at level 0 filters items;
  at levels 1–2 it **filters only the nested transactions** — every session in range still
  appears and page counts do not depend on it. For polled sources it matches their own type field
  (payments `paymentType`, adjustments `transactionType`, limits `limitType`; prizes never).
- `status` is deferred. The `/web` access token (O-2) and `/cc` auth (O-14) are deferred; the
  phase-4 stubs stay.

**Polled sources:** only status changes between versions (O-15 default); a CANCELLED, REJECTED or
EXPIRED payment stays visible and is not "cancelled". **Currency:** a session is single-currency;
an item whose currency differs from its session's is dead-lettered (O-4).

### Delta endpoint contract

`GET /wallet/delta` accepts a timestamp and returns up to **1000** changed rows. It is **open** (no
authentication) and returns **one mixed feed**: a JSON array of objects with one identical
structure — the union of the fields of the four polled tables — plus a `"source"` field naming
the table (user, 2026-11-03). Field names are **assumed** to be the source DDL columns in camelCase
(`paymentId`, `ownerId`, `prizeAmount`, …); the cursor parameter name (`wqs.delta.cursor-param`,
default `from`), the base URL, the page limit and the `source` value → source mapping
(`wqs.delta.sources`, default `walPayments`, `walClaimablePrizes`, `walManualAdjustmentRequests`,
`walLimits`) are configuration, so a real sample changes only the decoder, its fixtures and
config.

**Polled row → item** (phase 6):

| | Payments | Claimable prizes | Adjustment requests | Limits |
|---|---|---|---|---|
| `sourceId` | `paymentId` | `id` | `id` | `id` |
| owner | `ownerType`/`ownerId` | `playerId` (a player) | `userType`/`userId` | `userType`/`userId` |
| `occurredAt` | `created` | `created` | `issued` | `created` |
| version | `updated` | `updated` | `statusDate` | `updated` |
| amount | `amount`, + IN / − OUT (`paymentInOut`) | `prizeAmount` | `amount`, + DEPOSIT / − WITHDRAWAL | `amount`, null → 0 |
| type code | `paymentType` (null → `PAYMENT`) | `CLAIMABLE_PRIZE` | `transactionType` | `limitType` |
| product, batch | — | `productId`, `productType`, `batchId`, `subBatchId` | — | — |

Currency null → `XXX`. Only players are projected. Polled rows are never zero-amount-hidden and
never cancel anything. Timestamps without a zone are read as UTC; all are truncated to µs. Detail
is a per-source allowlist (no card, bank, email or XML data).

**Versioned apply.** First sight inserts like a stream item and records the version in the
locator (`"versionAt"`). A strictly newer version updates only `"detail"` (which carries the
status) in every view the source feeds; equal is a duplicate; older is ignored and counted
(`wqs.delta.stale`). A newer version that changes any immutable field (`occurredAt`, amount,
currency, type, product, batch, user) is dead-lettered `immutable-field-changed` — the O-15
default says only status changes, and refusing anything else loudly is safer than an untested
retract-and-reapply.

Two hazards follow from a timestamp-only cursor:

1. **Late commits.** A row stamped `T` may commit after WQS has polled past `T`. Mitigated by
   always polling from `lastTs − overlapWindow`, where the overlap safely exceeds the longest
   wallet write transaction. Re-reads of an unchanged version are absorbed by the version check
   (above); only a strictly newer version writes.
2. **Timestamp cliff.** If more than 1000 rows share one timestamp, advancing the watermark to
   `max(ts)` re-fetches the same page indefinitely. WQS **detects this condition and alarms
   loudly**; it must not silently stall or skip. A tiebreaker field on the delta response is
   expected to resolve this (open question O-1).

---

## 5. Data model

### 5.1 Tables

| Table | Purpose | Partitioning |
|---|---|---|
| `"wqWebItem"` | Level-0 rows for user views (transactions + prizes) | monthly on `"occurredAt"` |
| `"wqCcItem"` | Level-0 rows for the operator unified view (all 5 sources) | monthly on `"occurredAt"` |
| `"wqSession"` | Session rollup with precomputed aggregates | monthly on `"startedAt"` |
| `"wqPlay"` | Play rollup with precomputed aggregates | monthly on `"startedAt"` |
| `"wqWebHighLevel"` | L1/L2 page index for user views — one row per session or unsessioned item | monthly on `"occurredAt"` |
| `"wqCcHighLevel"` | L1/L2 page index for the operator view | monthly on `"occurredAt"` |
| `"wqCountMonth"` | Tier-1 pagination totals | **not partitioned** (yearly partitioning deferred; see below) |
| `"wqCountDay"` | Tier-2 pagination totals for partial months | monthly |
| `"wqItemLocator"` | Global `sourceId` → partition locator; idempotency gate | **not partitioned** |
| `"wqGroupLocator"` | `(userId, batchId, subBatchId)` → `"startedAt"` of the session / play row (§5.5) | not partitioned |
| `"wqPendingCancel"` | Cancels whose original has not yet arrived | not partitioned |
| `"wqDeadLetter"` | Events that repeatedly failed to project | not partitioned |
| `"wqIngestWatermark"` | Delta cursor (one row, `wallet-delta`, monotonic) | not partitioned |
| `"wqStreamOffset"` | RabbitMQ stream offset | not partitioned |
| `"wqTxType"` | Transaction-type code table (code → id, PLAY/WIN/NEUTRAL); `"wqProduct"` was dropped in V10 | not partitioned |

Monthly partitions are named from the parent with a period suffix: `"wqWebItem_2026_10"`.
Partition names are generated programmatically, so consistency matters more than aesthetics.

`"wqCountMonth"` is currently **unpartitioned**: at one row per user, view, page unit, product and
active month it stays small for years, and yearly partitioning would add a second rollover cadence
for no measured benefit yet. Whether to partition it yearly is deferred (plan, Deferred table).

### 5.2 Deliberate duplication across view tables

Transactions and claimable prizes are stored in **both** `"wqWebItem"` and `"wqCcItem"`.

This roughly doubles storage for those two sources. It is accepted deliberately: the user-facing
path is the overwhelming majority of traffic, and giving it an index containing no payments,
limits or manual adjustments keeps that index smaller, denser and more likely to stay resident in
Aurora's buffer cache. A single shared table would make the hot path pay for operator-only rows on
every index probe.

A second benefit is security by schema (§9): `/web` structurally cannot return operator-only
sources, because they are not in the table it queries.

### 5.3 Item tables

```sql
create table "wqWebItem" (
  "userId"        bigint      not null,
  "itemId"        bigint      not null,
  "source"        smallint    not null,   -- 1=TRANSACTION, 3=CLAIMABLE_PRIZE
  "sourceId"      text        not null,
  "occurredAt"    timestamptz not null,
  "productId"     int,
  "batchId"       text,                   -- session
  "subBatchId"    text,                   -- individual play
  "type"          smallint    not null,
  "amount"        numeric(20,4) not null,
  "currency"      char(3)     not null,
  "balanceAfter"  numeric(20,4),
  "cancelled"     boolean     not null default false,
  "cancelledBy"   text,                   -- sourceId of the cancelling event
  "cancels"       text,                   -- sourceId this event cancels
  "detail"        jsonb,                  -- product-specific fields (e.g. eInstant game data)
  "ingestedAt"    timestamptz not null default now(),
  primary key ("userId", "occurredAt", "itemId")
) partition by range ("occurredAt");
```

The primary key leads with `"userId"` and includes the partition key, which Postgres requires. That
single index serves the mandatory access pattern — user-scoped, range-bounded, newest-first — as an
index-only scan.

`"wqCcItem"` has the same shape, with `"source"` spanning all five values.

### 5.4 Why `"wqItemLocator"` is not partitioned

A cancel event references the original transaction's `sourceId` but carries no timestamp for it.
Since the item tables are partitioned by time, resolving that reference against a partitioned
index means probing every monthly partition — over sixty probes after five years, none of which
can be pruned.

```sql
create table "wqItemLocator" (
  "source"     smallint    not null,
  "sourceId"   text        not null,
  "userId"     bigint      not null,
  "occurredAt" timestamptz not null,
  "itemId"     bigint      not null,
  primary key ("source", "sourceId")
);
```

It covers **every ingested item from all five sources**, which makes it serve two purposes:

1. One index lookup resolves a cancel to its original's exact partition.
2. It is the **idempotency gate**. An `insert … on conflict do nothing` that conflicts means the
   event has already been applied, so it is dropped. This is what makes ingestion idempotent
   without any source-specific dedupe logic.

### 5.5 Rollups

Every rollup carries three required metrics, precomputed at write time:

| Metric | Session | Play |
|---|---|---|
| **total wins** — summed win amount | `"totalWins"` | `"totalWins"` |
| **total plays** — summed wagered amount | `"totalPlays"` | `"totalPlays"` |
| **number of plays** | `"playCount"` — plays in the session | `"txCount"` — transactions in the play |

"Total plays" is a **monetary amount**, not a count. The count is a separate metric, and at the
play level it counts the play's constituent transactions.

```sql
create table "wqSession" (
  "userId"                   bigint      not null,
  "batchId"                  text        not null,
  "startedAt"                timestamptz not null,
  "endedAt"                  timestamptz not null,
  "productId"                int,
  "currency"                 char(3)     not null,
  "txCount"                  int         not null,
  "txCountExclCancelled"     int         not null,
  "playCount"                int         not null,  -- number of plays
  "playCountExclCancelled"   int         not null,
  "totalPlays"               numeric(20,4) not null,  -- amount wagered
  "totalWins"                numeric(20,4) not null,  -- amount won
  "net"                      numeric(20,4) not null,  -- totalWins - totalPlays
  "totalPlaysExclCancelled"  numeric(20,4) not null,
  "totalWinsExclCancelled"   numeric(20,4) not null,
  "netExclCancelled"         numeric(20,4) not null,
  primary key ("userId", "startedAt", "batchId")
) partition by range ("startedAt");

create table "wqPlay" (
  "userId"                   bigint      not null,
  "batchId"                  text        not null,
  "subBatchId"               text        not null,
  "startedAt"                timestamptz not null,
  "endedAt"                  timestamptz not null,
  "productId"                int,
  "currency"                 char(3)     not null,
  "txCount"                  int         not null,  -- number of plays, at play level
  "txCountExclCancelled"     int         not null,
  "totalPlays"               numeric(20,4) not null,
  "totalWins"                numeric(20,4) not null,
  "net"                      numeric(20,4) not null,
  "totalPlaysExclCancelled"  numeric(20,4) not null,
  "totalWinsExclCancelled"   numeric(20,4) not null,
  "netExclCancelled"         numeric(20,4) not null,
  primary key ("userId", "startedAt", "batchId", "subBatchId")
) partition by range ("startedAt");
```

`"net"` is derivable from the other two but is stored anyway, consistent with the rule that reads
perform no arithmetic.

**Group key and labels, folded over every item.** `"startedAt"` = MIN(`"occurredAt"`) and
`"endedAt"` = MAX(`"occurredAt"`) over the group's items, cancelled or not; `"productId"` and
`"currency"` = LEAST over the items' non-null values. Every fold is order-independent, so any
arrival order converges to the same row (P-1). An item **earlier** than the current start
**re-anchors** the row: because `"startedAt"` is in the primary key and is the partition key, the
row is deleted and re-inserted at the new start (possibly in another monthly partition). A
cancellation item never rolls up, so a cancel never moves a row.

**`"wqGroupLocator"`.** A later transaction joining an existing session or play knows only its
`batchId` / `subBatchId`, not the `"startedAt"` in the row's primary key — the same problem
`"wqItemLocator"` solves for items (§5.4), solved the same way: an unpartitioned table keyed
`("userId", "batchId", "subBatchId")` holding the row's current `"startedAt"`, rewritten on
re-anchor. The session itself is keyed with `"subBatchId"` = `''`; that empty string is reserved,
because blank `batchId` / `subBatchId` values are normalised to null at the domain boundary
(`ProjectionItem`) — "no session" and "no play" have exactly one representation, and `''` is
unreachable from input.

Rollups carry a `"currency"` column with scalar sums. This is correct if a session never spans
currencies and extensible if it does (open question O-4).

> **OPEN DECISION — mixed-currency sessions.** Current behaviour: a session (and play) whose items
> carry different currencies stores LEAST of them — deterministic and arrival-order independent —
> while its money columns sum amounts across currencies regardless. Its page-index rows carry the
> same (session) currency in every view. Whether such a session should instead be rejected,
> split, or carry per-currency sums is for the product owner (tied to O-4).

### 5.6 High-level page index

At levels 1 and 2 a page unit is either a session or an unsessioned item (§7.4). Those live in
differently-shaped tables, so the merge is performed **at write time** into a narrow page index —
one per view, following the §5.2 reasoning:

```sql
create table "wqWebHighLevel" (
  "userId"               bigint      not null,
  "occurredAt"           timestamptz not null,  -- session "startedAt", or the item's "occurredAt"
  "kind"                 smallint    not null,  -- 0 = SESSION, otherwise the source type
  "refId"                text        not null,  -- "batchId" for a session, "sourceId" for an item
  "productId"            int,
  "currency"             char(3)     not null,
  "visibleExclCancelled" boolean     not null default true,
  primary key ("userId", "occurredAt", "kind", "refId")
) partition by range ("occurredAt");
```

This is a **page index, not a payload store.** It carries only what ordering, filtering and
counting require; payload is fetched by `"refId"` during assembly (§7.3). Keeping it narrow is the
point — it is the structure every L1/L2 query scans.

Consequences, all of them simplifications:

- **Paging stays a single covering index scan.** Counter-assisted offset (§7.6) and the index-only
  guarantee (§7.7) carry over to levels 1 and 2 completely unchanged, because the merge has
  already happened.
- **Counters count rows in this table**, so the high-level count needs no special arithmetic.
- **`"visibleExclCancelled"` collapses the subtlest rule in the design.** For a session it is true
  while at least one non-cancelled transaction remains; for an unsessioned item it is simply
  `not cancelled`. The `excludeCancelledTransactions` filter becomes a predicate on one column,
  and the counter rule becomes uniform: **decrement `"cntExclCancelled"` exactly when this flag
  flips true → false.** The conditional described in §6.3 is now maintained in one place instead of
  being recomputed per query.
- Sessions sort by `"startedAt"`. A transaction joining an existing session inserts no high-level
  row and correctly increments no `HIGH_LEVEL` counter, because the row already exists. If it is
  **earlier** than the session's start, the session re-anchors (§5.5) and its page-index row
  **moves** to the new key in each view — and its `HIGH_LEVEL` counters move with it (§5.7).
  A session's page row carries the session's `"productId"` and `"currency"` (LEAST, §5.5) in every
  view, not the individual item's.
- **Visibility is per view.** A session row in view V has `"visibleExclCancelled"` true while the
  session has at least one surviving (non-cancelled, non-cancellation) item whose source feeds V;
  so a session can be visible in `/cc` and hidden in `/web`. A hidden row is **revived** when a
  live item feeding that view joins later. An unsessioned item's row is visible iff the item is
  not cancelled.
- **Cancellation items are item rows, not page units.** A cancel event is stored in the item
  tables and counted at the `ITEM` page unit like any row, but it never rolls up, never gets a
  page-index row and never counts at `HIGH_LEVEL`.

Covering index: `("userId", "occurredAt" desc, "kind" desc, "refId" desc) include ("productId",
"visibleExclCancelled")`. `"kind"` and `"refId"` are key columns so the page index has a total
order for level 1/2 paging — rows sharing an `"occurredAt"` need a tie-break, exactly as items
need `"itemId"`.

### 5.7 Counters

```sql
create table "wqCountMonth" (
  "userId"            bigint   not null,
  "viewKind"          smallint not null,  -- 1=WEB, 2=CC
  "pageUnit"          smallint not null,  -- 0=ITEM (L0), 1=HIGH_LEVEL (L1/L2)
  "productType"       text     not null,  -- '' = ALL product types (unreachable from input)
  "typeId"            smallint not null,  -- 0 = ALL transaction types ("wqTxType" ids start at 1)
  "period"            date     not null,  -- first day of month, UTC
  "cntAll"            bigint   not null,
  "cntExclCancelled"  bigint   not null,
  primary key ("userId", "viewKind", "pageUnit", "productType", "typeId", "period")
);
```

`"wqCountDay"` is identical with `"period"` as a UTC date, partitioned monthly.

Design notes:

- **`"pageUnit"` has only two values.** `ITEM` counts rows in the view's item table, for level 0.
  `HIGH_LEVEL` counts rows in the view's high-level page index (§5.6) — sessions *and* unsessioned
  items — for levels 1 and 2. Levels 1 and 2 differ only in payload nesting, so they share
  counters entirely and always report identical page counts.
- **The `HIGH_LEVEL` increment rule follows from the page index.** Because the counter counts rows
  in `"wq*HighLevel"`, it increments exactly when a row is inserted there: once when a session is
  first created, and once per unsessioned item. An additional transaction joining an existing
  session inserts no row and so increments nothing — which is the correct behaviour, and getting
  it wrong would inflate page counts by the number of transactions per session.
- **Two count columns, not two rows**, because `excludeCancelledTransactions` is boolean.
- **Two dimensions, each with an ALL sentinel (phase 5, V10).** An `ITEM` row is counted under
  four keys — `(pt,t)`, `(pt,ALL)`, `(ALL,t)`, `(ALL,ALL)`; a null product type only under the two
  ALL-product keys. `HIGH_LEVEL` rows use `typeId = 0` only — `(pt,0)`, `('',0)` — because
  `transactionTypes` never changes level-1/2 page counts (§4.1). A total for request lists `P`, `T`
  is `Σ_{p∈P'} Σ_{t∈T'}` with `P' = P or {''}`, `T' = T or {0}`; exact because each item has one
  product type and one type. Filter sets never contain a sentinel (enforced by the filter records).
- **Rows are sparse.** They exist only for periods where the user had activity. A user active
  twenty days a year generates hundreds of counter rows, not thousands.
- **Counters are mutable in place.** A cancel arriving today can decrement a counter for a
  transaction from eight months ago, which rules out append-only counter storage and rules out
  sealing old partitions as read-only.
- **Sparse means deleted, not zeroed.** A decrement that brings `"cntAll"` to 0 deletes the row.
  Decrements are **strict**: they target a row the original's arrival created, so a missing row
  fails the transaction rather than being clamped, and a CHECK
  (`"cntAll" >= 0 and 0 <= "cntExclCancelled" <= "cntAll"`) backs this in the schema.
- **`HIGH_LEVEL` counters follow the row's current key.** They count page-index rows at the row's
  own (`"occurredAt"`, `"productType"`) — a session's start and its first item's product type — so when a session
  re-anchors or its product changes, its counters move from the old key to the new one.

### 5.8 Partition lifecycle

Partition creation is **application-managed**: a scheduled task ensures N months exist ahead of
the current date.

`pg_partman` is available on Aurora but is rejected, because the stock Testcontainers Postgres
image does not include it. App-managed DDL means CI, local development and production run
identical partition logic, and partition rollover — the one piece of time-dependent
infrastructure in the system — becomes testable locally.

**On-demand provisioning.** The runway only covers *now* onwards, but events arrive for any month:
the first deployment replays the stream from offset 0 (O-9 backfill), and a producer may send an
event dated past the runway. So `ProjectionWriter` provisions, **first and before any projection
write**, a partition in every partitioned table for each `"occurredAt"` month the batch touches
(those months also cover session/play re-anchor targets, page-index rows and `"wqCountDay"`
periods). The DDL runs in its **own** transaction, never inside the projection transaction —
`CREATE TABLE … PARTITION OF` takes an ACCESS EXCLUSIVE lock on the parent. Months known to exist
are cached per process; the `pg_inherits` check remains the source of truth.

**Sanity window.** A month after now + 12 months (`wqs.partition.max-months-ahead`), or before the
**provisioning floor** (`wqs.partition.provision-floor`, default 2010-01), is a garbage timestamp,
not a partition to create: the item is poison and takes the normal retry / per-item / dead-letter
path (§6.4), and the check runs before any DDL. The floor bounds the partitions a stream of garbage
timestamps can create to (now + 12 − floor) months × tables; it is set to the earliest month of
real history. There is deliberately **no per-batch cap** — it would dead-letter legitimate
deep-replay data. Above the floor a deep replay may create many small historical partitions, which
is acceptable at monthly granularity.

Partition names are `<table>_<uuuu>_<MM>` — the proleptic year, not year-of-era, which is ambiguous
for years ≤ 0; the two agree for every positive year, so no existing name changes.

Partition management is designed so detach-and-archive can be added later without schema change
(open question O-3).

---

## 6. Write path and scheduled jobs

```
RabbitMQ stream ──> StreamConsumer ──┐
  (sequential adaptive batches)      │
                                     ├──> ProjectionWriter ──> Aurora WRITER
/wallet/delta ──> DeltaPoller ───────┘      (one TX per batch)
  (4 sources, watermark + overlap)
```

Everything that mutates a `wq*` table goes through `ProjectionWriter`. Nothing else holds a write
connection. The stream consumer, the delta pollers and any future backfill loader are all merely
drivers of that one component.

### 6.1 Stream consumption

The wallet publishes to a **single** RabbitMQ stream. WQS consumes it **sequentially in adaptive
batches**: flush on either N events or a T-millisecond window, whichever comes first. A ~200ms
window delivers sub-second freshness while still collapsing counter updates.

Sequential consumption is chosen over a thread pool deliberately. With concurrent lanes over one
stream, offsets complete out of order, so only the contiguous acknowledged prefix can safely be
committed — requiring a hand-rolled watermark tracker, and a single stalled lane blocks offset
advance regardless.

**Resume and position.** The committed offset lives in `"wqStreamOffset"` (§6.2). On start the
consumer subscribes at **stored offset + 1** — subscribing at the stored offset would replay the
last committed event — and, with nothing stored, at **offset 0**: a first deployment replays the
whole stream (O-9 backfill; partitions are provisioned on demand, §5.8).

**Enablement and the single-consumer constraint.** The consumer is off unless
`wqs.stream.enabled=true` (env `WQS_STREAM_ENABLED=true`); the default is `false` so broker-less
contexts start. **Exactly one replica may enable it.** Nothing enforces this yet: two consumers
would project the same events and race on the stored offset. Single-active-consumer or a Postgres
advisory lock is deferred to before any multi-replica deployment; until then the constraint is
documented in configuration and every replica with the consumer off logs a WARN at startup.

**Scale-out path:** RabbitMQ super streams partitioned by `userId` give one sequential consumer
per partition, each with an independent offset, using the same consumer logic. At the measured
peak of under 100 transactions per second this is not needed; the consumer is kept
topology-agnostic so adopting it requires no rewrite.

### 6.2 The projection transaction

Per batch, in one transaction, in this order:

1. **Normalize** source events into items, resolving view routing (which view tables each source
   feeds).
2. **Idempotency gate** — insert into `"wqItemLocator"` with `on conflict do nothing`. A conflict
   means the event is already applied; drop it.
3. **Insert** into `"wqWebItem"` and/or `"wqCcItem"`.
4. **Resolve cancels** — look up the original via the locator, set `"cancelled"`, adjust the
   affected rollups and counters. An unresolvable reference is parked (§6.3).
5. **Drain pending cancels** matching any item just inserted.
6. **Upsert rollups** for touched `"batchId"` / `"subBatchId"`.
7. **Upsert high-level page-index rows** (§5.6) — one per newly created session, one per
   unsessioned item — and maintain `"visibleExclCancelled"`.
8. **Apply counter deltas** to `"wqCountDay"` and `"wqCountMonth"`.
9. **Commit the stream offset / delta watermark in the same transaction.**

**As implemented**, the order is per item, then per batch: provision partitions for the batch's
months in a separate transaction (§5.8); then for each item in turn — idempotency gate and insert
(steps 2–3), resolve or park it if it is a cancel (step 4), then its rollups, page-index rows and
counter deltas (steps 6–8); after all items, drain the pending cancels matching items this batch
inserted, each in its own savepoint (step 5, §6.3); finally the offset (step 9). Applying each
item's derived state before the next item keeps every fold order-independent; the numbered list
above is the logical decomposition, not a strict sequence of whole-batch passes.

Step 9 is the correctness story. The stream offset lives in `"wqStreamOffset"`, not in the broker,
so projection state and consumer position advance or roll back together. This yields
effectively-once processing instead of at-least-once plus a reconciliation job.

Grouping events by `userId` within a batch collapses counter contention to one update per user per
batch rather than one per event.

### 6.3 Cancellation semantics

A cancel is a new event naming the `sourceId` it cancels. Applying it:

- sets `"cancelled" = true` on the original item row;
- decrements `"cntExclCancelled"` at the `ITEM` page unit for the original's day and month;
- decrements the originating play's and session's `"txCountExclCancelled"` and excl-cancelled
  money totals (`"totalPlaysExclCancelled"`, `"totalWinsExclCancelled"`, `"netExclCancelled"`);
- decrements the session's `"playCountExclCancelled"` **only if** the affected play's surviving
  transaction count just reached zero — the same shape of conditional as the high-level flag
  below, one level further down;
- recomputes `"visibleExclCancelled"` on the corresponding high-level row (§5.6) — for a session,
  true while at least one non-cancelled transaction remains; for an unsessioned item, simply
  `not cancelled`;
- decrements `"cntExclCancelled"` at the `HIGH_LEVEL` page unit **exactly when that flag flips
  true → false.**

The flag is what makes this tractable. A session belongs in the excl-cancelled total while it
retains at least one non-cancelled transaction, and expressing that as a maintained boolean means
the condition is evaluated in one place at write time rather than recomputed per query. Both the
read filter and the counter rule then reduce to reading or watching one column. It is guarded by
property P-2 (§8).

**Early cancels.** A cancel can arrive before the item it cancels — different source, different
transport, no global ordering. Unresolvable cancels are parked in `"wqPendingCancel"` and drained
whenever a matching item is ingested. This makes ingestion order-independent.

**Resolution key (as implemented).** A cancel resolves against an original with the **same
source and the same user**: `"wqItemLocator"` is looked up by (cancel's source, `cancels`), and
the original's `userId` must equal the cancel's. A cancel naming another user's item, or an item
of another source, is treated as not seen and **parks** — it never fails the batch. Parked cancels
currently have **no expiry**; they surface through `wqs.pendingcancel.depth` and
`wqs.pendingcancel.oldest.age.seconds`. Expiry and alerting policy are TBD (deferred).

> **OPEN DECISION — cancel resolution scope.** The paragraph above says a cancel may arrive via a
> "different source", but the implementation (ruling R86) resolves a cancel only against an item
> of its **own** source, because different sources may share `sourceId` values and the locator is
> keyed by (source, sourceId). Which is right depends on how producers actually reference
> cancelled items (O-5, O-13); until decided, a cross-source cancel parks indefinitely.

**Multiple cancels.** A second, distinct cancel of an already-cancelled item changes no tally; it
only lowers `"cancelledBy"` to the least cancel `sourceId`, so the stored state does not depend on
which cancel arrived first. Cancellation items themselves count at `ITEM` only and never roll up
(§5.6).

**A drained cancel that fails** (an unexpected error, not a mismatch — mismatches park) is
applied in a savepoint: it is rolled back alone, removed from `"wqPendingCancel"` and
dead-lettered **itself**; the original whose arrival drained it stays projected and the batch
commits.

### 6.4 Failure handling

- **Batch failure** rolls back the whole transaction, leaving the offset uncommitted, so the batch
  is retried from the same position.
- **Poison events** are written to `"wqDeadLetter"` with their error after N retries; the offset
  then advances and an alarm fires. Ingestion keeps flowing and the bad event is retained for
  manual replay.

As implemented:

- A batch is tried **N times whole, then each item N times alone**; an item still failing is
  dead-lettered with `attempts` = 2N and its **own** stream offset. Its neighbours project.
- A message that cannot be mapped to an item, or whose body cannot be parsed at all, is
  dead-lettered at once (`attempts` = 1); an unparseable body is stored as
  `{"rawBase64": "…"}` — the exact bytes.
- **Offset rules.** The stored offset advances only past events that were projected or
  dead-lettered. A dead-letter written while earlier events are still buffered does not move the
  offset; the next batch's higher offset covers it. If a dead-letter write itself fails (the
  database is down), nothing moves: the consumer drops its buffer and, after a backoff,
  re-subscribes from the stored offset.
- **NUL fallback.** U+0000 is legal JSON but storable in neither Postgres `text` nor `jsonb`. It
  is rejected in known text fields at parse time (→ `rawBase64` row). If a normal dead-letter row
  still cannot be stored, a fallback row is written in its own transaction with null `sourceId`,
  `{"rawBase64": …}` of the serialised item and NUL-free error text — a dead-letter write cannot
  be defeated by the data it quarantines.
- **Start failure self-heals (phase 4).** The stream `Environment` is created lazily, so an
  unreachable broker at startup does not fail the context: a subscribe failure enters the same
  recovery/backoff loop as a mid-run failure. The app starts and serves reads throughout. A
  rethrown `Error` still installs the poller.

### 6.5 Scheduled jobs (Quartz)

All recurring work runs under **Quartz with a clustered JDBC JobStore**, not Spring `@Scheduled`.

**Why clustered, even though production starts as a single instance.** `@Scheduled` fires on every
instance. The moment WQS scales out for read capacity, three instances would poll
`/wallet/delta` concurrently and race each other's watermark updates — duplicating work and
risking a skipped page. Quartz guarantees exactly one instance runs each trigger. Specifying the
clustered JobStore now costs the `QRTZ_*` tables up front and behaves identically on one instance,
but means scaling out never requires revisiting scheduling. Getting this wrong is silent: the
pollers keep working, just incorrectly.

**Operational requirement:** clustered Quartz relies on database-coordinated locks and comparable
clocks across instances. Nodes must have synchronised time (NTP) and distinct instance ids; clock
skew produces duplicate or missed fires.

**Schema exception.** Quartz owns its DDL and uses fixed `QRTZ_*` uppercase names, which cannot
follow §3. Those tables therefore live in a **separate `quartz` schema**, keeping them out of the
`wq*` namespace and excluded from jOOQ code generation (§8.3). The DDL ships as a Flyway migration.

**The jobs:**

| Job | Purpose | Reference |
|---|---|---|
| `deltaPoll` (one job — the feed is mixed) | Poll `/wallet/delta` with watermark and overlap; disabled by default | §4, §6 |
| `partitionRollover` | Ensure N months of partitions exist ahead | §5.8 |
| `counterReconciliation` | Sample counters against actual counts, emit drift | §8.7 |
| `highLevelReconciliation` | Sample the high-level index against its sources | §8.7 |
| `pendingCancelSweep` | Report pending cancels older than a threshold | §6.3 |
| `deadLetterReport` | Surface dead-letter depth and age | §6.4 |

Every job class carries `@DisallowConcurrentExecution`. For the delta pollers this is a
correctness requirement, not tidiness: two overlapping polls of the same source would race the
same watermark row.

**Configuration from application properties.** Jobs are declared in config, not in code:

```yaml
wqs:
  jobs:
    delta-poll:
      enabled: false                   # closed by default, like the stream
      intervalSeconds: 5
      misfirePolicy: DO_NOTHING        # never queue catch-up fires
  delta:                               # poller settings (phase 6)
    base-url: http://wallet:8080       # no default; WQS_DELTA_BASE_URL
    cursor-param: from
    page-limit: 1000
    overlap: 120s
    max-pages-per-run: 20
    partition-rollover:
      enabled: true
      cron: "0 0 3 * * ?"
      monthsAhead: 3
    counter-reconciliation:
      enabled: true
      cron: "0 15 * * * ?"
      sampleSize: 500
```

A job accepts either `cron` or `intervalSeconds`. Bound via `@ConfigurationProperties` to a map of
job name → settings.

**Startup reconciliation of triggers.** Because a clustered JobStore persists jobs and triggers in
the database, changing a schedule in configuration does not by itself change the stored trigger. At
startup WQS reconciles declared configuration against stored Quartz state: register missing jobs,
reschedule changed triggers, pause disabled ones, and delete orphans whose configuration has been
removed. Without this step a cron change appears to be ignored, which is a confusing failure to
diagnose.

**Misfire policy matters for the pollers.** The default Quartz behaviour would queue catch-up fires
after downtime; for a poller that already catches up by advancing its own watermark, that produces
a thundering herd of redundant polls. `do-nothing` is correct — skip the missed fires and let the
next scheduled run read from the watermark.

**Cost note.** Delta polling every few seconds through a clustered JobStore means a trigger
acquisition round-trip to the writer per fire, per source. At this scale that is negligible, but it
is load on the writer rather than a reader, and so is sized accordingly in the write pool (§7.10).

---

## 7. Read path

```
GET /web/wallet-data/rest/v1/projection            ──> player from auth principal ──> "wqWebItem" / "wqSession"
GET /cc/wallet-data/rest/v1/projection/{playerId} ──> player from path variable  ──> "wqCcItem"  / "wqSession"
                                      │
                       ┌──────────────┴──────────────┐
                       ▼                             ▼
              exact total (counters)        page slice (counter-assisted offset)
                       └──────────────┬──────────────┘
                                      ▼
                             assembler → nested DTO
```

Both endpoints share one query pipeline. They differ only in how `userId` is resolved and which
item table they target.

### 7.1 Parameters

| Parameter | `/web` | `/cc` | Notes |
|---|---|---|---|
| player | **from principal** | **path variable `{playerId}`** | `/web` must never read it from the request (§9) |
| `from` | required | required | ISO-8601 with offset |
| `to` | required | required | ISO-8601 with offset |
| `aggregationLevel` | required | required | 0, 1 or 2 |
| `productTypes` | optional list | optional list | exact match on the item's product type (§7.2) |
| `transactionTypes` | optional list | optional list | level 0: filters items; levels 1–2: filters nested `txs` only (§4.1) |
| `excludeCancelledTransactions` | optional | optional | boolean, default false |
| `pageNumber`, `pageSize` | required | required | see §7.6 |

Paths (phase 5): `GET /web/wallet-data/rest/v1/projection` and
`GET /cc/wallet-data/rest/v1/projection/{playerId}`.

There is **no cap on the `from`/`to` span**. A request may cover the user's entire history, which
is what makes precomputed counters load-bearing rather than merely an optimization.

All timestamps are **UTC**. Counter periods are UTC days and months.

**Interval and index conventions**, stated explicitly because both are silent sources of
off-by-one defects:

- The range is **half-open: `[from, to)`** — `from` inclusive, `to` exclusive. This avoids the
  `23:59:59.999` boundary problem entirely and makes adjacent ranges tile without overlap or gap,
  which the counter decomposition in §7.5 depends on.
- `pageNumber` is **1-based**. Page 1 is the first page. `pagesAvailable == ceil(totalRowCount / pageSize)`,
  and `pagesAvailable == 0` when `totalRowCount == 0`.

Sort order is **fixed newest-first**: total order (`"occurredAt" desc`, `"itemId" desc`) at level
0 and (`"occurredAt" desc`, `"kind" desc`, `"refId" desc`) at levels 1–2, so ties on timestamp page
deterministically. Not client-configurable.

**Validation.** `pageNumber` and `pageSize` must be >= 1; `{playerId}` is a positive decimal long;
each list takes at most 50 values (repeated or comma-separated, trimmed, blanks dropped); `from > to`
is 400, while `from == to` is an empty 200 (half-open empty interval, totals 0). A non-empty
`transactionTypes` list naming no known type yields, at level 0, an empty, correct page without
running SQL; at levels 1–2 sessions still appear, with empty `txs`. The optional
`wqs.read.max-page-size` clamps `pageSize`, and the response reports the effective value (§7.9).
Error bodies are `ProblemDetail` with fixed text; `instance` is the endpoint template, never the
request URI.

### 7.2 View selection

- **Transactions history** and **eInstant games** are both served from `"wqWebItem"`, distinguished
  by product type: the client passes `productTypes` (e.g. `instants`). Which product types are
  eInstant is configuration (`wqs.einstant.product-types`, default `[instants]`), used by the ingest
  invariant of §7.4.
- **Unified data** is served from `"wqCcItem"`.

`"wqProduct"` and the integer product id were dropped in phase 5 (V10); product id and type are
stored as text on every row (§4.1). Counter totals for a list of product types sum one counter row
per listed type (§5.7).

### 7.3 Aggregation levels

Because `aggregationLevel` is mandatory, routing happens at the controller boundary — no fallback
branch, no `CASE` expressions in SQL.

| Level | Paged table | Page unit | Assembly |
|---|---|---|---|
| 0 | `"wqWebItem"` / `"wqCcItem"` | item | none |
| 1 | `"wqWebHighLevel"` / `"wqCcHighLevel"` | high-level item | split the page's `"refId"`s by `"kind"`; one query for session children, one for unsessioned item payloads |
| 2 | `"wqWebHighLevel"` / `"wqCcHighLevel"` | high-level item | plus a `"wqPlay"` query between session and item |

Levels 1 and 2 page the **high-level index** (§5.6) rather than `"wqSession"`, because a page unit
may be either a session or an unsessioned item (§7.4). The merge happens at write time, so the
slice is a single covering index scan at every level.

Assembly then reassembles the page in the order the index returned, fetching payload by `"refId"`:
two to four indexed queries per request regardless of level, each bounded by the page size.

Children are **assembled at read time**, not materialized as nested JSONB at write time. The
distinction that matters for "no computation on read" is *aggregate* computation — sums, group-bys,
window functions over many rows — and all of that is precomputed into the rollups. Fetching ~50
parent rows and their children by indexed id lookup is microseconds, and keeps filters like
`excludeCancelledTransactions` expressible directly in SQL rather than requiring a separate
materialized JSONB variant per filter combination.

### 7.4 Unsessioned items at levels 1 and 2

At levels 1 and 2 the page unit is the **high-level item**, which is either a **session** or an
**item with no `"batchId"`**. Both are peers in one chronological sequence, and **both count toward
pagination**. Unsessioned items are always included; there is no opt-out.

This resolves what was previously risk R-1. When only sessions were page units, the unsessioned
set had to be returned in full on every page and was therefore unbounded. Treating unsessioned
items as page units means they are paged like anything else, so no part of the response can grow
without limit and no truncation flag is needed.

**eInstant transactions are always part of a session**, so `"batchId"` is never null for an
eInstant product. Consequences:

- The **eInstant view never produces unsessioned items at all.** At levels 1 and 2 every eInstant
  transaction nests under a session with nothing left over.
- The unsessioned set is therefore non-empty only for non-eInstant products on `/web`, and for the
  operator-only sources on `/cc`.
- This is a **data invariant, not merely an observation**: a non-null `"batchId"` on every
  eInstant-product item is asserted at ingest (an unsessioned eInstant item is dead-lettered as
  `einstant-unsessioned`; cancellations are exempt, since they never enter the page index) and
  guarded by P-7 (§8.1). It is not a check constraint because the eInstant product types are
  configuration (`wqs.einstant.product-types`).

Whether claimable prizes carry a `"batchId"` remains open (O-12); it determines how large `/web`'s
unsessioned contribution is, but no longer whether the response is bounded.

### 7.5 Exact totals by range decomposition

```
total = Σ "wqCountMonth"[whole months inside range]
      + Σ "wqCountDay"  [whole days in the partial months at each end]
      + live COUNT      [the two partial boundary days only]
```

The live portion is always bounded to one user × one day, whether the range spans a week or a
decade. `excludeCancelledTransactions` selects `"cntExclCancelled"` instead of `"cntAll"`; the
algorithm is otherwise unchanged.

**Degenerate cases** the decomposition must handle, each covered by property P-5:

- `from` and `to` fall within the **same day** → a single live count, no counter reads.
- `from` and `to` fall within the **same month** → daily counters plus boundary days, no monthly
  counter read.
- `from` or `to` falls exactly on a **day or month boundary** → because the interval is half-open
  (§7.1), that period is wholly included or wholly excluded with no partial-day count.

A five-year arbitrary-timestamp range resolves to roughly 59 monthly rows, 58 daily rows and two
live day counts — under 200 narrow index-only rows for an exact total over a span that may cover
a million transactions.

### 7.6 Counter-assisted offset

Keyset pagination is excluded by requirement: the UI must show "page X of Y". Naive offset
pagination would mean `OFFSET 75000` for page 1500, walking 75,000 index entries to discard them.

Instead, the counters are walked to locate which segment contains the requested offset, and
the real query runs against that segment with the small residual:

```
remaining = (page - 1) * pageSize
for each segment in range, newest first:
    if remaining < counter[segment]:
        target = segment; residualOffset = remaining; break
    remaining -= counter[segment]

one key query from `from` up to the target segment's end, OFFSET = residualOffset
```

Deep pagination becomes a handful of narrow counter reads plus a partition-local scan of at most a
few thousand index entries. This delivers roughly keyset-like cost while preserving exact page
numbers, and is the payoff that makes the uncapped date range acceptable.

**As implemented (phase 4).** The walk covers **every decomposition segment** (§7.5) — month, day
and live — not months only, giving finer residuals. Day counters are read once per contiguous DAY
run (at most two: the leading and trailing partial months). After the walk, **one** ordered key
query runs from `from` up to the target segment's end with the residual offset, in total order
(`"occurredAt" desc`, `"itemId" desc`); partition pruning and Merge Append handle a page that
straddles into older months. The payload is then fetched by key (`"itemId" = any(array)`, within
the `"occurredAt"` bounds). Each request runs in one reader transaction (§7.10).

Correctness is guarded by property P-6 (§8), which asserts equivalence against a naive `OFFSET`
reference implementation.

### 7.7 Indexing

**One covering index per item table**, using `INCLUDE`:

```sql
-- conceptually, per partition
("userId", "occurredAt" desc) include ("cancelled", "productId", "batchId", "subBatchId", ...)
```

Filter predicates are evaluated against index tuples in an index-only scan, never touching the
heap. This trades a slightly wider scan for not maintaining three or four additional B-trees on
every insert — the right direction given seconds-level freshness requirements. Because
counter-assisted offset already confines every scan to a single month's partition, "slightly
wider" is bounded to one user-month.

Crucially, the index never has to serve a `COUNT`; counters do that. Totals and slicing being
served by **different structures** is what lets each stay simple.

**As created (phase 4, V9).** Item tables:
`("userId", "occurredAt" desc, "itemId" desc) include ("cancelled", "productId")` — only the
columns level 0 filters by; the `"itemId"` key column makes the tie-break index-ordered. The
high-level tables follow §5.6: `("userId", "occurredAt" desc, "kind" desc, "refId" desc) include
("productId", "visibleExclCancelled")`, the key columns again giving a total order. A test asserts the level-0 key query is an index-only scan on it.

**Rebuilt in phase 5 (V10).** Items: `("userId", "occurredAt" desc, "itemId" desc) include
("cancelled", "productType", "type")`; high-level: same key, `include ("productType",
"visibleExclCancelled")`.

**Added in phase 5 (V12)** for level 1–2 assembly, so each session's items are fetched by its own
key rather than by the page's time span: partial `("userId", "batchId", "occurredAt") where
"batchId" is not null` on both item tables, and `("userId", "batchId", "startedAt")` on `"wqPlay"`.
They cost one extra B-tree write per sessioned item — the measured need this section requires (a
long-lived session otherwise makes the fetch scan every newer item of the user).

Additional indexes are added only when the benchmark harness (§8) shows a measured need.

### 7.8 Response shape

Per the API contract (`docs/reference/api-contract.md`, §4.1), every level uses one envelope:

```jsonc
{
  "pageNumber": 2,          // 1-based
  "pagesAvailable": 98,
  "pageSize": 50,           // effective (clamped) size
  "totalRowCount": 4870,    // L0: items; L1/L2: sessions + unsessioned items
  "data": [ ... ]
}
```

- **Level 0:** `data` holds transaction objects (`txId`, `kind`, `created`, `productId`,
  `productType`, `transactionType`, `amount`, `currency`, `balanceAfter`, `cancelled`, `batchId`,
  `subBatchId`, `detail`); null fields are omitted; `status` is deferred.
- **Level 1:** `data` holds sessions (`kind: "SESSION"`, `batchId`, `created`, `endedAt`,
  `productId`, `productType`, `currency`, `totalPlay`, `totalWin`, `amount` = `totalWin` −
  `totalPlay`, `playCount`, `txs: [transaction…]`) interleaved by time with unsessioned items,
  which are plain transaction objects.
- **Level 2:** as level 1, but a session nests `subitems: [{subBatchId, playTimestamp,
  playAmount, winAmount, amount, txs: [transaction…]}]`. Session items with no `subBatchId`
  stay in the session's own `txs` (omitted when empty).
- With `excludeCancelledTransactions`, nested `txs` follow level-0 semantics: cancelled items are
  hidden, cancellation rows are not — including a cancel dated after its session's `endedAt`, and
  one whose play is not listed, which then sits in the session's own `txs`. At level 2 a play with
  no surviving transaction is omitted, so `playCount` equals the listed plays.
- `txs` (level 1), `subitems` (level 2) and each play's `txs` are always present, possibly `[]`.
- `transactionTypes` filters only transactions nested in a session; unsessioned top-level entries
  are page units and are not filtered by it.
- Totals are the stored rollup values — the excl-cancelled variants when
  `excludeCancelledTransactions` is set — never recomputed on read. `transactionTypes` filters only
  the nested `txs`; sessions and their totals are unaffected.

There is no separate unsessioned array and no truncation flag: unsessioned items are page units (§7.4).

### 7.9 Pagination policy

- **Page size is client-chosen with no hard maximum.**
- **Known risk (R-2).** A request such as `pageSize=500000` at level 2 can produce an enormous
  response and hold an Aurora connection for a long time. A configurable ceiling is implemented
  but **ships disabled**, so operations can clamp it without a release.
- A page beyond the last page returns **200 with an empty body** and correct `pagesAvailable`. This
  avoids spurious errors when live ingestion changes the page count between requests.

### 7.10 Aurora reader/writer split

Ingestion uses the **writer** endpoint. All queries use **reader** endpoints, scaling
horizontally without touching the write path.

**Accepted property:** Aurora replica lag (typically tens of milliseconds) means a committed
transaction may briefly not be visible to a query. For a retrospective history view this is
acceptable, and it is recorded here so it is not later triaged as a bug.

**As implemented (phase 4).** The writer is the `@Primary` `DataSource` and `DSLContext` (built over
Boot's jOOQ configuration, so Spring-managed transactions are unchanged); the reader has its own
Hikari pool. Pools are named `writer` and `reader`. The reader pool is **read-only, REPEATABLE
READ**, and each request runs in a **single reader transaction**, so counters and items come from
one snapshot. Every `DSLContext` injected into `wqs.read..` must be `@ReaderDsl` (§8.5), so a read
cannot silently land on the writer.

Connection pools are sized separately — a small writer pool (≈10–16) and a larger reader pool — so
a burst of operator queries cannot starve ingestion.

---

## 8. Testing

### 8.1 Properties (Jqwik)

**Opt-in since phase 5.** Property classes carry `@Tag("property")`; `./gradlew build` excludes
them and `./gradlew propertyTest` runs them (60-minute timeout). By decision of the project owner
they are kept but not run routinely — the build-time cost was too high — so CI correctness rests on
deterministic fixed-dataset tests that reuse the same oracles (`ProjectionInvariants`,
`NaiveOffsetOracle`, live COUNT). Run `propertyTest` before a release that touches ingest,
counters or paging.

| # | Property | Guards |
|---|---|---|
| P-1 | Any permutation or duplication of an event sequence converges to identical projection state | Locator idempotency gate; pending-cancel parking |
| P-2 | Every stored counter equals an actual `COUNT` over the projection, for both count columns | Entire pagination contract; the session-counter-on-cancel rule (§6.3) |
| P-3 | Concatenating all pages in order reproduces the full ordered result set — no gap, no duplicate | User-visible pagination correctness |
| P-4 | For `"totalPlays"`, `"totalWins"` and every count, session value = Σ play values = Σ transaction values, in both the all and excl-cancelled variants; `"net"` = `"totalWins"` − `"totalPlays"` everywhere; and L1 page count = L2 page count | Write-time aggregation (§5.5), including the `"playCountExclCancelled"` conditional |
| P-5 | Counter-derived total equals a live `COUNT` for arbitrary `from`/`to` | Two-tier range decomposition (§7.5) |
| P-6 | Counter-assisted offset returns exactly the rows naive `OFFSET` returns | The riskiest optimization in the service (§7.6) |
| P-7 | Every item whose product is an eInstant product carries a non-null `"batchId"`, and the eInstant view's unsessioned set is always empty | The eInstant session invariant (§7.4), unenforceable as a check constraint |
| P-8 | The high-level index holds exactly one row per session plus one per unsessioned item — no duplicates, no omissions — and `"visibleExclCancelled"` agrees with the underlying items | The write-time merge (§5.6); its oracle is a direct query over sessions and null-`batchId` items |

**Oracle-based testing is the core technique here.** P-2, P-5 and P-6 each compare a fast
production path against a slow but obviously-correct equivalent. The naive `OFFSET` implementation
is therefore **retained permanently as test-only code** — it is an asset, not dead code.

**Generators must reflect real shapes**, not uniform randomness. Volumes are power-law: a small
number of users hold a disproportionate share of 150M rows. Generators produce skewed activity,
clustered sessions, cancels arriving both before and after their originals, and events landing in
already-projected months.

### 8.2 Integration testing

Testcontainers runs Postgres (pinned to the Aurora major version) and RabbitMQ with the stream
plugin. Every integration test executes real Flyway DDL and real jOOQ queries. Repositories are
not mocked, because the component most likely to be wrong is the SQL.

**Stated limitation.** Testcontainers Postgres is not Aurora. It does not reproduce Aurora's
storage layer, reader-endpoint replica lag, or planner behaviour at scale. Correctness is proven
locally; absolute performance is not.

### 8.3 jOOQ code generation

Gradle spins a throwaway Testcontainers Postgres, runs Flyway, generates jOOQ classes from the
migrated schema, then compiles. Generated code therefore cannot drift from migrations, and the
build requires no external database. Docker is consequently a **build** dependency, not merely a
test dependency; confirmed available in CI and locally.

**Partition children must be excluded from code generation.** jOOQ generates a class per table,
and partitions are tables — left unconfigured, codegen emits `WqWebItem_2026_10`,
`WqWebItem_2026_11` and so on, with the generated source growing every month in perpetuity.
Queries target the parent table and let Postgres route, so the children are never referenced in
code. The codegen configuration must therefore exclude them by pattern:

```
excludes = .*_\d{4}_\d{2}
```

`flyway_schema_history` and the entire `quartz` schema (§6.5) must also be excluded; both are
infrastructure bookkeeping with no place in generated code.

Use the **official `jooq-codegen-gradle` plugin** (available since jOOQ 3.19), not the older
third-party Gradle plugin.

**Generator strategy — decided in phase 1 and never revisited.** `DefaultGeneratorStrategy`
uppercases identifiers, which flattens camelCase word boundaries: `"occurredAt"` would generate
the identifier `OCCURREDAT`. A custom strategy preserving database names verbatim is therefore
required, so generated identifiers read exactly like the schema:

```java
public class AsInDatabaseStrategy extends DefaultGeneratorStrategy {
    @Override
    public String getJavaIdentifier(Definition definition) {
        return definition.getOutputName();   // "occurredAt" stays occurredAt
    }
    // Deliberately does NOT override getJavaGetterName / getJavaSetterName — see below.
}
```

**Override only `getJavaIdentifier`.** jOOQ's documented `AsInDatabaseStrategy` example also
overrides `getJavaGetterName` and `getJavaSetterName` to return `"get" + outputName`, which for
`"occurredAt"` yields `getoccurredAt()` — not a JavaBean accessor. MapStruct (§2.3) would fail to
recognise it as a property. Overriding the identifier alone gives identifiers that read like the
schema *and* conventional accessors: `WqWebItem.occurredAt` with `getOccurredAt()`. Copying the
whole documented example is the trap here.

jOOQ's declarative matcher strategy is an equivalent alternative if regex configuration is
preferred over a Java class. Either way the choice is effectively a second naming convention
layered on §3, and changing it later renames every identifier in every query — so it is fixed in
phase 1.

**Where the schema is visible.** The migrations are the authoritative, reviewable, diffable
definition. The generated classes mirror them in Java: a `Tables` class, a `TableImpl` subclass per
table with a typed `TableField` per column, a `Record` type per table, plus `Keys` and `Indexes`.
Generated sources live under `db/build/` and are **not** committed, so they appear in the IDE but
not in code review; schema changes are reviewed as migration diffs instead. This is acceptable
precisely because codegen reconciles the two at build time — a renamed column breaks compilation
at every usage, which is what reviewing generated code would otherwise be for. The practical
friction is that a migration edit requires re-running codegen and a Gradle sync before the IDE
sees new columns.

Licensing note: the jOOQ **Open Source Edition** covers PostgreSQL in full and is the correct
edition here. Commercial licensing applies only to commercial databases. Aurora PostgreSQL speaks
PostgreSQL, so the `POSTGRES` dialect applies directly.

### 8.4 Benchmark harness

Since index additions are to be evidence-driven:

- a volume generator with configurable user counts, power-law activity distribution, session and
  play fan-out, and cancel rate;
- a driver exercising both endpoints across page depths, range widths and aggregation levels;
- captured `EXPLAIN (ANALYZE, BUFFERS)` output asserting scans remain index-only;
- recorded baselines so index changes are justified by a measured delta.

**Runs on Testcontainers Postgres only.** Results are therefore **relative regression signals, not
validated Aurora performance**. A green benchmark must not be read as a production guarantee.

### 8.5 Architecture tests (ArchUnit)

The usage and layering rules in §2.2 and §10 are the kind that decay silently: violating them is
easy, feels locally reasonable, and goes unnoticed until the structure is gone. ArchUnit converts
them from prose into build failures.

| Rule | Enforces |
|---|---|
| `api/` must not depend on `org.jooq..` or the generated package | §2.2 rules 2, 3 |
| No call to `DSL.field(String)` or `.sql(..)` outside an explicitly annotated allowlist | §2.2 rule 1 |
| `api/web` and web query classes must not depend on `WqCcItem` | §9 control 3 — security by schema |
| Nothing depends on `org.springframework.data.jpa..` | Prevents regression to the superseded pattern |
| Layering: `api` → `read`/`projection` → `domain`; `ingest` → `projection`; nothing depends on `api` | §10 |
| ~~Production code must not depend on the naive-`OFFSET` oracle~~ — **not added (phase 4)** | the oracle lives in test sources, which main cannot compile against; the rule could never fail |
| Every `DSLContext` injection point in `wqs.read..` is `@ReaderDsl` (constructor, `@Autowired`/`@Inject`/`@Bean` parameters and fields; plain methods taking a `DSLContext tx` are exempt) | §10 — a read never silently lands on the writer. Replaces "only `projection/` may inject the writer" (the writer is the unqualified primary and ingest, db and jobs also write) |
| `wqs.api..` other than `wqs.api.cc..` must not call `ServletRequest`/`WebRequest` `getParameter*`/`getQueryString` or use `ServletUriComponentsBuilder` | §9 control 1 |
| MapStruct mappers must not reside in `api/` | §2.3 rule 1 |
| `jobs/` classes must not be depended on by anything else | §10 — jobs are entry points, never collaborators |

As of phase 4 the web/cc table rule runs on real classes, and each rule has a fixture proving it can
fail. The third rule is the most valuable: §9 claims `/web` cannot leak operator data because it queries
a table that structurally cannot contain it. This makes that claim checkable, so reusing a
`"wqCcItem"` query in the web path fails the build rather than becoming an incident.

**Governance.** Every rule must trace to a numbered decision in this document, and must carry a
comment naming it. A rule that cannot be traced to a decision is a snapshot of incidental
structure, not a rule, and must not be added. The failure mode to avoid is a large suite of
incidental rules that obstruct legitimate refactoring and are then routinely suppressed.

ArchUnit lands in **phase 1** (§15), before there is any structure to violate.

### 8.6 Property-based testing as a team practice

Jqwik is a mental shift, not merely a new API: the difficulty is finding properties, not writing
them. The common adoption failures are properties that restate the implementation, and generators
that produce only uninteresting data.

WQS is a favourable first encounter because **P-2, P-5 and P-6 are oracle comparisons** — "the
fast path must equal the slow path" — which require no invariant-finding intuition, since the
correct answer already exists. Jqwik's shrinking then reports the *minimal* failing case, which
for a pagination defect is worth a great deal of debugging time.

Two standing rules:

1. **Properties do not replace example-based tests.** Properties cover invariants; examples cover
   specific known cases and every fixed bug. A regression test naming the actual incident
   communicates more than a property that incidentally covers it.
2. **Generator quality is the real work.** A property over weak generators passes vacuously.
   Generators are reviewed with the same care as production code (see §8.1 for the required
   distributions).

Properties are introduced one phase at a time per §15, rather than all six at once.

Test-suite practice (phase 4): `jqwik.shrinking.default = FULL` is set globally, because BOUNDED
shrinking abandons in-flight database-writing tries on the shared container.

### 8.7 Production invariant checking

Two properties are proven in CI against generated data but can still drift in production, where
real cancels and real out-of-order arrivals live. Both get a **reconciliation job** (§6.5) that
samples and emits a drift metric:

| Job | Samples | CI counterpart |
|---|---|---|
| `counterReconciliation` | stored counters against actual counts | P-2 |
| `highLevelReconciliation` | high-level index rows against sessions and null-`batchId` items, including `"visibleExclCancelled"` | P-8 |

The second exists because the high-level index is a **materialised merge** (§5.6). A read-time
union could never disagree with its sources; a materialised one can. That is the cost accepted
when the merge moved to write time, and this job is how the cost is managed.

**`ProjectionInvariants` is the shared oracle.** The slow-but-obvious SQL that recomputes counters,
rollups and group locators, the page index and cancellation state from the item rows is one
class, used by the CI properties (P-2, P-4, P-8) and, in phase 7, by both reconciliation jobs —
so CI and production check the same thing. It lives in test code today; phase 7 moves it to main
code (its string SQL then needs a `@RawSqlJustified`).

Both emit **drift as a gauge, alarmed at any non-zero value** (§11). Correctness is not a
percentage target — pagination that silently lies about page counts is an incident, not a
degradation.

---

## 9. Security

`/web` resolves `userId` from the authenticated principal; `/cc` accepts it as a parameter. The
worst possible defect in this service is `/web` honouring a client-supplied `userId` and serving
one user another user's financial history.

Controls:

1. The shared query pipeline accepts `userId` only as an **already-resolved value**. `/web`'s
   request binding has no path to request parameters for it.
2. An explicit test asserts that `/web/wallet-data/rest/v1/projection?userId=<other>` ignores the parameter
   entirely. This is obvious now and is exactly the kind of property that regresses quietly during
   a later refactor.
3. Operator-only sources are excluded from `/web` **by table choice**, not by a `WHERE` clause.
   `"wqWebItem"` structurally cannot contain payments, limits or manual adjustments. Enforcement
   by schema beats enforcement by predicate.

Authentication mechanism is open (O-2). A `PrincipalResolver` abstraction isolates it so the
mechanism can change without touching the query pipeline.

**As implemented (phase 4).** `wqs.web.principal.mode` is `none` (default: every `/web` request is
401) or `trusted-header` (the gateway-injected option of O-2). The header defaults to
`X-Authenticated-User-Id` only when unset; a blank or non-token name fails startup rather than
falling back. Exactly one header value is accepted and it must be a positive decimal long;
anything else is 401. Control 2's test exists.

`/cc` has no authentication (O-14), so it is **deny-by-default**: `wqs.cc.access: deny|open`
defaults to `deny` and answers 403 on every `/cc` request before binding; `open` logs a startup
WARN and must be used only on a trusted network.

---

## 10. Module layout

```
wqs/
├── settings.gradle.kts
├── db/                          # Flyway migrations + generated jOOQ classes
│   ├── src/main/resources/db/migration/V*__*.sql
│   └── build.gradle.kts         # Testcontainers-backed codegen task
└── app/
    └── src/main/java/<pkg>/
        ├── api/web|cc/          # controllers, DTOs, OpenAPI, parameter validation
        ├── read/                # query builders per level, counter reader,
        │                        #   offset resolver, assemblers, response mappers
        ├── ingest/stream|delta/ # consumer + offset store; pollers + watermarks
        ├── projection/          # ProjectionWriter, normalizers, rollup and
        │                        #   counter updaters, pending-cancel drain,
        │                        #   source-payload mappers
        ├── jobs/                # Quartz job classes + config-driven registration
        ├── domain/              # ViewKind, PageUnit, AggregationLevel, Money
        └── config/              # writer/reader datasources, pools, Quartz,
                                 #   job properties binding
```

**MapStruct mappers live in `read/` and `projection/`, never in `api/`** (§2.3). A mapper touches
both jOOQ types and DTOs by definition, so putting one in `api/` would violate §2.2 rule 2 and
fail the ArchUnit check. The mapper is the boundary and belongs on the inside of it.

**`jobs/` holds Quartz job classes only** — thin wrappers that delegate to `ingest/`,
`projection/` or maintenance components. No business logic lives in a job class, so a job can be
triggered manually or tested directly without the scheduler.

Two modules rather than one or six. `db` isolates code generation because generated classes are a
build artifact, so a migration change rebuilds exactly one jar. Splitting further would add Gradle
ceremony without isolation that packages do not already provide at this size.

**Datasource routing is explicit.** Read services receive a reader-bound `DSLContext`; the
projection writer receives a writer-bound one. AOP routing on `@Transactional(readOnly = true)` is
rejected as implicit and easy to get wrong silently — a read accidentally landing on the writer
would not surface until load.

---

## 11. Observability

Observability is a first-class requirement, not a closing phase. **Every phase ships its own
instrumentation**, so the component built in that phase is observable the day it exists.

Stack: **Micrometer → Prometheus / Amazon Managed Prometheus** via a scrape endpoint,
**OpenTelemetry** traces to a collector, and structured JSON logs.

### 11.1 The four signals that matter

Everything below serves one of four questions. If only four dashboards existed, these are they:

| Signal | Question | Headline metric |
|---|---|---|
| **Freshness** | Is the projection current? | `wqs.ingest.lag.seconds` |
| **Performance** | Are reads fast? | `wqs.read.duration` |
| **Correctness** | Is the projection telling the truth? | `wqs.reconcile.*.drift` |
| **Runway** | Will it break on a future date? | `wqs.partition.runway.months` |

The fourth is unusual and easy to omit. A failed partition rollover is invisible until midnight on
the first of the month, at which point every insert fails. It is the highest-severity *silent*
failure in the design, so it gets a first-class signal.

### 11.2 Metrics

**Implementation status after phases 1–3.** Implemented: every ingestion metric below except the
`wqs.delta.*` family (phase 6, with the pollers); `wqs.deadletter.depth`;
`wqs.partition.runway.months`; `wqs.job.duration` / `wqs.job.outcome` / `wqs.job.misfires`.
**(phase 4)** The level-0 read-path metrics below are implemented with every meter pre-registered,
and Hikari pool tagging (`pool=writer|reader`) is done; levels 1–2 read metrics follow in phase 5.
Tracing (§11.3) is still not implemented. Deferred, each marked in its table: the
`wqs.reconcile.*` drift gauges (phase 7, with the reconciliation jobs) and Aurora replica lag
(phase 7). Query-backed gauges are computed on scrape and cached (~60s, ~15s for offset lag), so
none can freeze when the code that would have "set" it stops running.

**Ingestion**

| Metric | Type | Tags | Notes |
|---|---|---|---|
| `wqs.ingest.lag.seconds` | gauge | source | `now()` − `"occurredAt"` of last projected event; until the first projection, seconds since consumer start (never a reassuring 0) |
| `wqs.ingest.stream.offset.lag` | gauge | — | stream head offset − committed offset; head = broker `committedChunkId` (a lower bound, short by at most one chunk); NaN when no head is known |
| `wqs.ingest.batch.size` | histogram | — | |
| `wqs.ingest.batch.duration` | timer | — | |
| `wqs.ingest.events` | counter | source, outcome | `projected` \| `duplicate` \| `deadlettered` |
| `wqs.pendingcancel.depth` | gauge | — | |
| `wqs.pendingcancel.oldest.age.seconds` | gauge | — | an *aging* set means cancels never resolve |
| `wqs.delta.rows.fetched` | histogram | source | *phase 6* |
| `wqs.delta.page.saturated` | counter | source | polls returning exactly `pageLimit` — saturation; *phase 6* |
| `wqs.delta.watermark.age.seconds` | gauge | source | detects a stuck poller; *phase 6* |
| `wqs.delta.timestampcliff` | counter | source | the unhandleable condition in §4; *phase 6* |

The `duplicate` outcome is a signal, not noise: a rising duplicate ratio means the delta overlap
window is wider than it needs to be, or the stream is replaying.

**Read path**

| Metric | Type | Tags | Notes |
|---|---|---|---|
| `wqs.read.duration` | timer | endpoint, aggregationLevel, pageDepthBucket | *phase 4–5* |
| `wqs.read.partitions.touched` | histogram | aggregationLevel | *phase 4–5* |
| `wqs.read.residual.offset` | histogram | — | offset remaining after the counter walk; *phase 4* |
| `wqs.read.counter.rows` | histogram | — | counter rows summed per request; *phase 4* |
| `wqs.read.pagesize` | histogram | endpoint | monitors R-2 directly; *phase 4* |
| `wqs.read.totalitems` | histogram | endpoint | *phase 4* |

`partitions.touched` and `residual.offset` together are the observable proxy for **"is
counter-assisted offset still working?"** Both should stay small and flat. If either climbs, the
optimisation in §7.6 is degrading — probably because counters are sparse where they were assumed
dense — and reads will slow before any query is logically wrong.

**Correctness**

| Metric | Type | Notes |
|---|---|---|
| `wqs.reconcile.counter.drift` | gauge | §8.7; any non-zero value is an incident; *phase 7* |
| `wqs.reconcile.highlevel.drift` | gauge | §8.7; *phase 7* |
| `wqs.deadletter.depth` | gauge | rows in `"wqDeadLetter"`, cached ~60s |

**Infrastructure**

| Metric | Type | Notes |
|---|---|---|
| HikariCP metrics | — | **tagged by pool: `writer` \| `reader`.** Writer starvation is a named failure mode (§7.10); pool tagging *phase 4* |
| `wqs.partition.runway.months` | gauge | months of partitions provisioned ahead |
| `wqs.job.duration` | timer | tagged by job (§6.5) |
| `wqs.job.outcome` | counter | job, result |
| `wqs.job.misfires` | counter | job |
| Aurora replica lag | gauge | from CloudWatch, or probed against a reader; *phase 7* |

### 11.3 Tracing

OpenTelemetry, with **trace context propagated from RabbitMQ stream message headers**, so an
ingest batch links back to the wallet operation that produced the event. That cross-service link is
the main reason to prefer OTel here over a local-only approach.

- **Ingest:** span per batch, child spans per projection step — locator gate, item insert, rollup
  upsert, high-level upsert, counter deltas, offset commit. The step breakdown is what tells you
  *which* part of a slow batch is slow.
- **Read:** span per request, child spans for the counter walk, the slice query, and each assembly
  query.

### 11.4 Logging

Structured JSON with trace and span ids. Because this is wallet data, logging is deliberately
minimal: **`userId` appears as an identifier only, and amounts, balances and `"detail"` payloads are
never logged at `INFO`.** Financial values appear in logs only at `DEBUG`, which is off in
production.

### 11.5 SLOs

Proposed targets, expected to be tuned against real traffic:

| SLO | Target |
|---|---|
| Projection freshness | p99 `ingest.lag` < 5s |
| Read latency, level 0 | p99 < 150ms |
| Read latency, levels 1–2 | p99 < 400ms |
| Read availability | 99.9% |
| Correctness | drift = 0, always |

Correctness has no percentage. Any drift means page counts are wrong.

### 11.6 Alarms

| Alarm | Condition | Why it matters |
|---|---|---|
| Freshness breach | `ingest.lag` p99 > 5s for 5 min | SLO breach |
| **Partition runway** | `partition.runway.months` < 2 | **inserts will fail at a month boundary** |
| Counter drift | `reconcile.counter.drift` ≠ 0 | pagination is lying |
| High-level drift | `reconcile.highlevel.drift` ≠ 0 | page counts and ordering wrong |
| Poller stuck | `delta.watermark.age` > 10× poll interval | silent data gap |
| Timestamp cliff | `delta.timestampcliff` > 0 | unhandleable until O-1 |
| Dead-letter rate | sustained increase | systemic ingest break, not one bad row |
| Pending cancels aging | `pendingcancel.oldest.age` > 24h | cancellations not being applied |
| Writer pool saturation | active = max for 1 min | ingestion starving (§7.10) |
| Page size abuse | `read.pagesize` p99 above threshold | R-2 materialising; the §7.9 ceiling can be enabled |

The partition-runway alarm deserves its emphasis: it is the only alarm here that fires *before*
anything is broken, and it is the only failure whose impact is total.

---

## 12. Known risks

| # | Risk | Status |
|---|---|---|
| R-1 | ~~Unsessioned set at L1/L2 is unbounded and repeats on every page~~ | **Resolved 2026-11-01.** Unsessioned items became page units (§7.4), so nothing is unbounded. Row retained so R-2…R-6 keep their numbers. |
| R-2 | No hard maximum on `pageSize` (§7.9) | Accepted; disabled-by-default configurable ceiling provided |
| R-3 | Benchmarks run only on Testcontainers Postgres, not Aurora (§8.4) | Accepted; results documented as relative signals |
| R-4 | Aurora replica lag makes very recent transactions briefly invisible (§7.10) | Accepted as a property of the design |
| R-5 | Delta timestamp cliff is unhandleable until the tiebreaker field exists (§4) | Mitigated by loud alarm; resolution depends on O-1 |
| R-6 | jOOQ is new to the team and is being introduced on the most performance-critical service (§2.1) | Accepted; mitigated by build-time codegen and oracle-based properties |

---

## 13. Open questions

| # | Question | Blocking? |
|---|---|---|
| O-1 | What is the additional `/wallet/delta` field, and does it provide a within-timestamp tiebreaker? | Not for phase 1; required before the delta poller is production-safe |
| O-2 | How is the `/web` principal established — JWT validated in WQS, gateway-injected header, or session lookup? | Required before `/web` ships |
| O-3 | Retention policy: keep all partitions indefinitely, or detach and archive after N years? | No; design is archive-ready |
| O-4 | ~~Is the wallet multi-currency, and can a single session span currencies?~~ **Answered (§4.1): a session is single-currency; a mismatching item is dead-lettered** | —
| O-5 | Which of the five sources can actually be cancelled? | No; the locator covers all sources regardless |
| O-6 | ~~The remaining "few other" query parameters~~ **Answered: `docs/reference/api-contract.md`, interpreted in §4.1** | —
| O-7 | ~~Exact response field contracts per view~~ **Answered: `docs/reference/api-contract.md`, interpreted in §4.1 (`status` deferred)** | —
| O-13 | Full field lists from the RabbitMQ stream and from each `/wallet/delta` source | **Stream: answered (§4.1). Delta: structure answered (one union feed + `source`, §4); JSON field names assumed from the DDL.** Structural columns are known; unenumerated source fields land in `"detail" jsonb` (§5.3) and are promoted to real columns only if they need indexing or filtering |
| O-8 | Aurora PostgreSQL major version | Assumed 16; affects Testcontainers pinning |
| O-9 | Backfill strategy for the existing 150M+ rows | Deferred by decision; `ProjectionWriter` is loader-agnostic so any loader can drive it |
| O-10 | ~~How does a client select the eInstant view — explicit product ids, or a category parameter?~~ **Answered: a `productTypes` list matching the item's product type string** | —
| O-11 | Gradle DSL: Kotlin or Groovy | Assumed Kotlin DSL |
| O-12 | ~~Do claimable prizes carry a `"batchId"`?~~ **Answered:** `walClaimablePrizes` has nullable `batchId` / `subBatchId`, so prizes may be sessioned or not (§7.4) | — |
| O-15 | ~~Which fields of each polled source can change between versions — status only, or also amount, product, type? And is a `walPayments` row with status CANCELLED / REJECTED / EXPIRED "cancelled" for `excludeCancelledTransactions`?~~ **Default adopted (§4.1): only status changes; cancelled/rejected/expired payments stay visible** | —
| O-16 | ~~Source user ids are `varchar(64)`; WQS uses `bigint`. Are player ids always numeric? Should rows with `userType` ≠ 1 (non-players) be projected?~~ **Answered: `ownerId` = `"{userType}-{userId}"`, numeric; only userType 1 is projected** | —
| O-17 | ~~Source `created` is `timestamp` without zone — which zone does the wallet write? (assumed UTC)~~ **Answered for the stream: ISO-8601 UTC. Delta sources assumed the same** | —
| O-14 | How is `/cc` authenticated/authorized? Today it is deny-by-default (`wqs.cc.access`, §9) | **Blocking before `/cc` is opened beyond a trusted network** |

---

## 14. Decision log

Decisions made during design, with their rationale in brief.

| Decision | Rationale |
|---|---|
| Virtual threads, not WebFlux | jOOQ is blocking; virtual threads give concurrency without reactive types |
| jOOQ over JPA and over `JdbcClient` (§2.1) | Keeps index-only scans enforceable; one source of schema truth; no ORM semantics needed |
| camelCase tables **and** columns | Explicit requirement; jOOQ quotes by default so only Flyway needs care |
| Per-view tables with duplication | Keeps the hot user-facing index small and cache-resident; security by schema |
| `"wqItemLocator"` unpartitioned, all sources | One-probe cancel resolution; doubles as the idempotency gate |
| Children assembled on read | Aggregates are precomputed; nesting assembly is microseconds and keeps filters in SQL |
| Two-tier counters, UTC periods | Exact totals over an uncapped range at bounded cost |
| Counter-assisted offset | Preserves "page X of Y" at near-keyset cost |
| Single covering index per table | Protects seconds-level freshness; counters remove the need to index for `COUNT` |
| Sequential stream batches | Avoids out-of-order offset commits; <100/sec peak leaves ample headroom |
| Offset stored in Postgres, same transaction | Effectively-once instead of at-least-once plus reconciliation |
| Pending-cancel parking table | Makes ingestion order-independent for cancels |
| Dead-letter and advance | One malformed event must not stop history for every user |
| App-managed partitions, not `pg_partman` | Identical DDL across CI, local and production; rollover becomes testable |
| Naive `OFFSET` kept as test oracle | The clever path's speed with the obvious path's confidence |
| Explicit datasource qualifiers | Implicit AOP routing fails silently |
| ArchUnit, from phase 1 (§8.5) | Converts §2.2 and §10 prose rules into build failures, including the §9 security boundary |
| Custom `AsInDatabaseStrategy` for codegen, fixed in phase 1 (§8.3) | The default strategy flattens camelCase identifiers; changing this later renames every identifier in every query |
| Rollups carry total wins, total plays and a play count at both levels (§5.5) | Required metrics; "total plays" is a monetary amount, and at play level the count is of transactions |
| Unenumerated source fields go to `"detail" jsonb`, promoted later if needed | Lets phases 1–3 proceed before O-13 is answered, without guessing at columns |
| Quartz with a clustered JDBC JobStore, not `@Scheduled` (§6.5) | `@Scheduled` fires on every instance; scaling out would race the delta watermarks. Silent when wrong |
| Jobs declared in application properties, reconciled against stored triggers at startup (§6.5) | A persistent JobStore ignores config changes otherwise, which is a confusing failure to diagnose |
| Quartz `QRTZ_*` tables in a separate `quartz` schema | Quartz owns its DDL and cannot follow §3; a separate schema contains the exception and keeps codegen clean |
| MapStruct for all boundary mapping (§2.3) | Unmapped fields fail the build, extending the one-source-of-truth property into the mapping layer |
| Override only `getJavaIdentifier` in the generator strategy (§8.3) | jOOQ's own documented example breaks JavaBean accessors and therefore MapStruct |
| Lombok where it removes real boilerplate; records preferred (§2.4) | Records cover immutable types more clearly; `lombok-mapstruct-binding` is mandatory or mappers silently drop fields |
| Observability instrumented per phase, not as a closing phase (§11) | Each component is observable the day it exists; alarms and SLOs defined rather than deferred |
| Reconciliation jobs for both counters and the high-level index (§8.7) | A materialised merge can drift from its sources; this is the managed cost of moving the merge to write time |
| Properties introduced one per phase (§8.6) | jOOQ and property-based testing are two new mental models; ArchUnit is only a test API |
| Unsessioned items are page units, interleaved with sessions (§7.4) | Bounds the response, gives one count and one page number, and eliminated risk R-1 |
| The sessions/unsessioned merge happens at **write** time, into a per-view high-level page index (§5.6) | Keeps L1/L2 paging a single covering index scan, so §7.6 and §7.7 carry over unchanged; write path has ample headroom at under 100/sec |
| `"visibleExclCancelled"` maintained on the high-level row (§6.3) | Collapses the session-counter-on-cancel conditional into one maintained boolean |

---

## 15. Suggested implementation phasing

This design covers a whole service, which is more than one sitting of work. The phases below are a
proposed decomposition so each lands as something verifiable rather than a half-wired system. The
detailed implementation plan will be produced separately.

Each phase ends with its named properties passing — not with code merely compiling.

| Phase | Scope | Ends green on |
|---|---|---|
| 1 | Gradle two-module skeleton; Flyway + Testcontainers-backed jOOQ codegen incl. **generator strategy and exclusions**; Lombok + MapStruct processor chain; **Quartz schema and config-driven job registration**; item tables, partitions, partition rollover job; `"wqProduct"`, `"wqItemLocator"`; **ArchUnit rule set** | Partition rollover tests; trigger reconciliation tests; codegen reproducibility; architecture tests |
| 2 | Stream consumer, `ProjectionWriter`, idempotency gate, offset store in Postgres | **P-1** |
| 3 | `"wqSession"` / `"wqPlay"` rollups; high-level page index and `"visibleExclCancelled"`; `"wqCountDay"` / `"wqCountMonth"`; cancellation and `"wqPendingCancel"` | **P-2**, **P-4**, **P-8** |
| 4 *(done)* | Range decomposition; counter-assisted offset; `/web` + `/cc` at level 0; `PrincipalResolver` (`none` / `trusted-header`, pending O-2); covering indexes; reader/writer datasources; consumer start self-heal; broker end-to-end test | **P-3**, **P-5**, **P-6**, security test (§9) |
| 5 *(done)* | Source contract (§4.1): real stream decoder, V10/V11 string product dimensions and `"wqTxType"`, product-type × type counters, ingest rules; API reshaped to the contract with list filters; levels 1 and 2: paging the high-level index, session/play assembly, interleaved response | Fixed-dataset tests for P-3/P-5/P-6 at both page units, P-4 at the response, P-7; property tests made opt-in |
| 6 *(done)* | Delta poller for the four polled sources (mutable: versioned upsert of status/detail, immutable-field changes dead-lettered, §4); watermarks with overlap; timestamp-cliff alarm; dead-letter path | P-1 extended across both ingestion paths |
| 7 | Reconciliation jobs, SLO dashboards and alarms, benchmark harness and recorded baselines | Drift metrics at zero; alarms firing on injected faults; baseline capture |

**Observability is not phase 7.** Each phase ships the metrics for what it builds — ingest lag and
batch metrics in phase 2, drift gauges in phase 3, read latency and offset-health metrics in
phases 4–5. Phase 7 adds the reconciliation *jobs*, the dashboards, the alarms and the benchmark
baselines, not the instrumentation itself.

**Phase ordering rationale.** Ingestion precedes reads because the read path's properties need real
projected data to assert against, and the Jqwik generators built in phase 2 are reused as the data
source for phases 4 and 5. The delta pollers come late deliberately: they share `ProjectionWriter`
with the stream consumer, so they add four source normalizers rather than new machinery, and O-1
is still open.

Phases 1 through 4 constitute a working, correct, user-facing level-0 service. That is the natural
first milestone.
