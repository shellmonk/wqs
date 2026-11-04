# WQS Owner-Month Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Move from the current 9-table model to the 7-table owner-month model (picture: https://claude.ai/artifact/LzwjQAuEYdxZZNy4GF7yA2): store everything (zero amounts, non-player owners), decide visibility at read time through a read policy, catch duplicates with a unique key on the main table (no locator), page levels 1–2 by merging sessions with standalone rows (no timeline), and count per owner-month with exact combinations (`wqOwnerMonth`, replacing `wqPrjCount`). The API and every response stay the same.

**Architecture:** WQS is not live: `V1__baseline.sql` is edited in place. Four tasks, each changing schema and code together and ending green. Endpoint knowledge (`/web` vs `/cc`) leaves the data and the domain: the read layer passes a `ReadPolicy` (sources, owner type, hide zero amounts) to every query.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring `JdbcClient`, Flyway, Postgres 16, ArchUnit, Testcontainers, Groovy-DSL Gradle.

**Spec:** `docs/superpowers/specs/2026-10-31-wallet-query-service-design.md` (§5.0 is replaced at the end by this model). User decisions 2026-11-04: one main table for all five sources, monthly partitions; sessions and plays tables; `wqIngestCursor`; `wqOwnerMonth` keyed by source (domain knows no CC/WEB); duplicates and cancels assumed rare; everything is written, including zero amounts and non-player owners; `wqOwnerBounds` derived, not a table (controller call); unresolved cancels resolved after 7 days; busy-month day counts deferred to the benchmark.

## Global Constraints

- **No API behaviour change**: same responses, totals, page counts, ordering, filters. `/web` shows sources TRANSACTION and CLAIMABLE_PRIZE; `/cc` all five; both show only `ownerType = 1` (players) and never zero-amount rows. These rules live in one `wqs.read.ReadPolicy` per endpoint and are bound into every query.
- **Owner identity is `(ownerType, userId)`** in every owner-keyed table and query; `ownerId "2-1717"` and `"1-1717"` never share rows.
- Test policy (user): jqwik property tests are opt-in and **never run**; sources must still compile. Deterministic tests only; every new guard shown RED before GREEN.
- camelCase identifiers double-quoted; every value bound; `JdbcClient` only; one DB transaction per batch; one read-only REPEATABLE READ `@ReaderTx` per request.
- Each task ends with `./gradlew clean build` green, run alone.

## Final schema

| Table | Key | Partitioned | Notes |
|---|---|---|---|
| `"wqPrjTransaction"` | `("ownerType","userId","occurredAt","transactionId")` | monthly on `"occurredAt"` | all sources, zero amounts and non-players included; unique `("source","sourceId","occurredAt")`; covering index `("ownerType","userId","occurredAt" desc,"transactionId" desc) include ("source","productType","type","cancelled","zeroAmount")`; partial index for standalone rows `… where "batchId" is null`; session-member index `("ownerType","userId","batchId","occurredAt") where "batchId" is not null`. `"zeroAmount"` is a stored generated column `("amount" = 0)` |
| `"wqPrjSession"` | `("ownerType","userId","batchId")` | no | totals all/excl; `"txCount"` (non-zero), `"zeroTxCount"`; `"visibleExclCancelled"`; index `("ownerType","userId","startedAt" desc,"batchId" desc) include ("productType","visibleExclCancelled")` |
| `"wqPrjPlay"` | `("ownerType","userId","batchId","subBatchId")` | no | totals; `"zeroTxCount"` |
| `"wqOwnerMonth"` | `("ownerType","userId","month","source","unit","productType","type","zeroAmount")` | no | `"cnt"`, `"cntExclCancelled"`; `unit` TX or ENTRY; `''` = none (never "all"); ENTRY rows have `"type" = ''`; rows deleted at 0 |
| `"wqPrjPendingCancel"` | `("source","cancelSourceId")` | no | `"targetSourceId"`, owner, `"parkedAt"`, the cancel's data |
| `"wqIngestCursor"` | `("feed")` | no | unchanged |
| `"wqDeadLetter"` | id | no | unchanged |

Removed: `wqPrjTransactionLocator`, `wqPrjTimeline`, `wqPrjCount`, views `wqPrjWebTransaction` / `wqPrjCcTransaction`, `viewKind`, `onWeb`, `SourceKind.views()`, the `sessioned-source-not-on-every-view` rule, the zero-amount and non-player skips.

## Decisions

| # | Decision |
|---|---|
| D1 | `ReadPolicy(Set<SourceKind> sources, short ownerType, boolean includeZeroAmounts)`; `ReadPolicy.WEB = ({TRANSACTION, CLAIMABLE_PRIZE}, 1, false)`, `ReadPolicy.CC = (all, 1, false)`. Every read query binds `"source" = any(?)`, `"ownerType" = ?` and (unless included) `not "zeroAmount"`; an ArchUnit/source guard requires every read query class to take a `ReadPolicy`. |
| D2 | Duplicates: `insert … on conflict ("source","sourceId","occurredAt") do nothing`; a conflict is a duplicate. Polled rows: on conflict compare `"versionAt"` (newer → replace `"detail"`; equal → duplicate; older → stale); an immutable-field change is dead-lettered as today. |
| D3 | Cancel target lookup: read the owner's months from `wqOwnerMonth`, query `wqPrjTransaction` by `("source","sourceId")` with `"occurredAt"` bounded to each of those months (one statement, OR of month ranges or `unnest` of month starts). Same-source rule unchanged. Not found → park. Every insert probes `wqPrjPendingCancel` by `(source, sourceId)`. A daily Quartz job `pendingCancelResolution` removes parked cancels older than 7 days (the cancel row itself stays as an ordinary row), logging each. |
| D4 | Levels 1–2 page entries = the owner's sessions (by `"startedAt"`) merged with their rows where `"batchId" is null`, newest first; ties ordered by `(time desc, kind desc, id desc)` where kind 0 = session, else the source code; `excludeCancelled` uses `"visibleExclCancelled"` (sessions) and `not "cancelled"` (standalone rows). |
| D5 | Totals: sum `wqOwnerMonth` for whole months in `[from, to)` + live COUNT on the covering index for the two partial edge months (skipped when the owner has no row for that month). The walk is per month; the key query is bounded to the months the page spans (from the counts). |
| D6 | Session `"txCount"` and play counts exclude zero-amount rows; `"zeroTxCount"` counts them; amounts are unaffected. A zero-amount standalone row counts as an ENTRY with `zeroAmount = true`. |

---

### Task 1: Store everything; owner type; read policy

- [ ] Schema: add `"ownerType" smallint not null` as the first key column of every owner-keyed table (transactions, locator for now, pending cancel owner, sessions, plays, timeline, counts); `"zeroAmount"` generated column on transactions; `"zeroTxCount"` on sessions/plays.
- [ ] Ingest: decoders keep `userType` → `ProjectionTransaction.ownerType`; remove the non-player and zero-amount skips; zero-amount rows are inserted and counted per D6; counters gain the zero flag where they key rows (still `wqPrjCount` in this task — add a `"zeroAmount"` key column).
- [ ] Read: introduce `ReadPolicy` (D1) and thread it through every query (still using the views/viewKind in this task where they remain, but adding `ownerType = ?` and `not "zeroAmount"`).
- [ ] Tests: a non-player row with the same numeric id as a player is stored and never visible to that player; a zero-amount row is stored, deduplicated, counted apart, and invisible on both endpoints; a cancel of a zero-amount row resolves; existing API tests unchanged. Commit `feat(projection): store everything; owner type in keys; read policy`.

### Task 2: Duplicates on the main table; no locator; pending-cancel resolution

- [ ] Schema: unique `("source","sourceId","occurredAt")` on `"wqPrjTransaction"`; drop `wqPrjTransactionLocator`.
- [ ] Writer per D2/D3 (insert-on-conflict, versioned apply on the main table, cancel lookup through the owner's months, pending probe on every insert).
- [ ] `pendingCancelResolution` Quartz job (config-driven, enabled, daily, 7-day threshold) per D3.
- [ ] Tests: duplicate stream message; delta overlap re-read; newer/equal/older version; cancel before/after target; cancel whose target never arrives is resolved after the threshold; the stream/poller concurrency test. Commit `refactor(projection): duplicates by unique key on the main table; no locator`.

### Task 3: Levels 1–2 without the timeline

- [ ] Schema: drop `wqPrjTimeline`; session reading index and the standalone-row partial index per the final schema; `"visibleExclCancelled"` on sessions.
- [ ] Read per D4: one merged key query for levels 1–2; assembly unchanged otherwise (≤ 4 statements per page).
- [ ] Writer: no timeline writes; re-anchor only updates `"startedAt"`.
- [ ] Tests: paging oracle at levels 1–2 (naive merge oracle), ties between a session and a standalone row at the same instant, excludeCancelled, EXPLAIN test that the merge uses both indexes. Commit `refactor(read): levels 1–2 merge sessions and standalone rows`.

### Task 4: `wqOwnerMonth`; no views, no viewKind

- [ ] Schema: `"wqOwnerMonth"` per the final schema replaces `"wqPrjCount"`; drop views `wqPrjWebTransaction`/`wqPrjCcTransaction`, `viewKind`, `onWeb`; remove `SourceKind.views()` and the `sessioned-source-not-on-every-view` rule.
- [ ] Writer: one TX count row per transaction and one ENTRY row per new session or standalone row, exact combination (no "all" rows); cancels move `"cntExclCancelled"`; re-anchor moves the session's ENTRY count between months.
- [ ] Read per D5 with `ReadPolicy`: totals, month walk, key query bounded to the page's months; `ViewSourceIsolationTest` replaced by the D1 guard; the payment-never-on-/web tests kept green via the policy.
- [ ] Tests: totals = live COUNT over multi-year ranges at both units and both policies; filtered totals (products × types, excludeCancelled); key-query pruning EXPLAIN (only the page's months opened). Commit `refactor(read): owner-month counts; endpoint policy instead of views`.

### Task 5 (controller): spec §5.0, diagrams, memory

## Execution notes

Executed 2026-11-04 with subagent-driven development on main. Per the user's speed preference, per-task reviews were folded into one final whole-branch review; Tasks 3 and 4 were merged into one change (user-approved).

Commits: 3109bbc (T1), 074b408 (T2), 4f24482 (T3+4), 5404263 (final-review fix wave).

### Rulings

- R1: T2's cancel lookup derived months from wqPrjCount; T3+4 switched `OwnerMonths` to wqOwnerMonth.
- R2: wqOwnerBounds is derived, not a table (user agreed).
- `"zeroAmount"` = `amount = 0` for stream rows only (spec hides zero amounts only for stream transactions; polled zero rows stay visible on /cc).
- Sessions are filtered by the read policy through `"firstSource"`; their ENTRY count is kept under that source.
- Security (automated review + final review M1): with the sessioned-source rule gone, a batched payment could leak into /web session totals; the ingest rule was restored as `SourceKind.carriesBatch()` (reason name `sessioned-source-not-on-every-view` kept).
- Final review I1: seeding `"zeroTxCount"` by scanning history on every new play/session was replaced by incremental maintenance via the helper table `"wqPrjPendingZero"`.
- Accepted: first-seen polled rows probe `(source, sourceId)` across partitions (keeps immutable-field dead-lettering); a stream id resent with a different timestamp is stored twice (duplicates rare); M6 non-player rows go through ingest rules; M7 more stream/poller deadlock retries.

### Deferred minors

- Zero-amount stream row and polled prize creating the same session concurrently can miss each other (same race as before under read committed); would show as `zeroTxCount` drift.
- `"wqPrjPendingZero"` rows for groups that never get a non-zero row stay (tiny).
- The D1 source guard reads source text; a table name built at runtime would bypass it (none today).
- Busy owner-months: month-granular walk leaves a larger residual offset; the Phase 7 benchmark decides on day counts.
- Spec §5.1–§7.7 prose still describes older structures; §5.0 is authoritative.
