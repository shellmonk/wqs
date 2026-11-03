# WQS Data Model Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Simplify the schema from 15 tables to 9 with no change to the API or to any response: one item table (views per endpoint), one timeline table, unpartitioned sessions/plays without a group locator, one per-day counter table, one ingest cursor, a Java enum instead of `wqTxType`, the `wqPrj` prefix on projection tables, and a key query bounded by the page's own day range.

**Architecture:** WQS is not live, so `db/src/main/resources/db/migration/V1__baseline.sql` is edited in place (no new migration). Each task changes schema and code together and ends green. SQL is text, so a schema change breaks tests, not compilation — every task runs the full suite.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring `JdbcClient`, Flyway, Postgres 16, ArchUnit, Testcontainers, Groovy-DSL Gradle.

**Spec:** `docs/superpowers/specs/2026-10-31-wallet-query-service-design.md`. User decisions 2026-11-03: proposals 1, 2, 4 accepted; page index kept as one table; counters kept (no COUNT(*) design); `wqTxType` → enum; `wqPrj` prefix on projection tables; span table rejected in favour of counter-bounded key queries. Reference picture: https://claude.ai/artifact/LzwjQAuEYdxZZNy4GF7yA2 (names there predate the prefix).

## Global Constraints

- **No behaviour change** at the API: same responses, totals, page counts, ordering, filters, security. The existing tests are the safety net; tests that assert table names or internal structure are updated, never deleted to make the suite pass.
- Test policy (user): jqwik property tests are opt-in and **never run**; their sources must still compile. Deterministic tests only; every new guard shown RED before GREEN.
- camelCase identifiers double-quoted in all SQL; every value bound; no jOOQ.
- One database transaction per batch on the write side; one read-only REPEATABLE READ `@ReaderTx` per request on the read side.
- `/web` code reads only `"wqPrjWebItem"` (view) and timeline rows with `"onWeb"`; the `ViewSourceIsolationTest` source scan is updated to the new names (web code must not name `wqPrjCcItem`; cc code may name both).
- Each task ends with `./gradlew clean build` green, run alone.

## Final schema (target of the whole plan)

| Table / view | Key | Notes |
|---|---|---|
| `"wqPrjItem"` | `("userId","occurredAt","itemId")`, monthly partitions | every source, stored once |
| view `"wqPrjWebItem"` | — | `select * from "wqPrjItem" where "source" in (1, 3)` (TRANSACTION, CLAIMABLE_PRIZE) |
| view `"wqPrjCcItem"` | — | `select * from "wqPrjItem"` |
| `"wqPrjItemLocator"` | `("source","sourceId")` | unchanged columns |
| `"wqPrjPendingCancel"` | `("source","cancelSourceId")` | `"type"` now text |
| `"wqPrjSession"` | `("userId","batchId")`, **not partitioned** | index `("userId","startedAt" desc,"batchId" desc)`; `firstSourceId/firstSource` kept |
| `"wqPrjPlay"` | `("userId","batchId","subBatchId")`, not partitioned | |
| `"wqPrjTimeline"` | `("userId","occurredAt","kind","refId")`, monthly partitions | `"onWeb" boolean not null`; covering index as today plus `"onWeb"` |
| `"wqPrjCount"` | `("userId","viewKind","pageUnit","productType","type","day")`, monthly partitions on `"day"` | `"type" text`, `''` = ALL types; `"productType"` `''` = ALL |
| `"wqIngestCursor"` | `("feed")` | `"streamOffset" bigint null`, `"watermark" timestamptz null`, `"updatedAt"` |
| `"wqDeadLetter"` | unchanged | |
| `quartz.*` | unchanged | |

Removed: `wqWebItem`, `wqCcItem`, `wqWebHighLevel`, `wqCcHighLevel`, `wqGroupLocator`, `wqCountMonth`, `wqCountDay`, `wqStreamOffset`, `wqIngestWatermark`, `wqTxType`.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | `wqs.domain.TransactionType` enum lists the codes from source-schema.sql with `direction()` (PLAY: TT_BUY, BUY, BET, TT_PERIODIC_CHARGE; WIN: TT_PRIZE_IN, PRIZE; else NEUTRAL); `TransactionType.directionOf(String code)` returns NEUTRAL for codes not in the enum. Items, pending cancels and counters store the **code as text**; the wire-format regex `[A-Z0-9_]{1,64}` stays. `ProjectionItem.type` becomes `String`. | User: enum. Unknown codes still projected, never rejected. |
| D2 | `transactionTypes` filtering matches the text code directly; the "unknown codes → empty page without SQL" short-circuit is removed (unknown codes simply match nothing — same result). | No registry to consult. |
| D3 | Counter totals sum `"wqPrjCount"` rows over the requested days: whole UTC days in `[from, to)` from the counter table, the two partial boundary days by a live COUNT as today. `RangeDecomposition` keeps DAY and LIVE segments only. The offset walk walks days. | One table; years = ~thousands of tiny rows, one index range. |
| D4 | Key query bounds: the walk also finds the day where the page **ends** (running count ≥ residual + limit); the key query's lower bound becomes that day's start (or `from` if earlier), not `from`. | Only partitions holding the page's rows are opened (span-table question). |
| D5 | Session/play re-anchor (a late earlier item) only updates `"startedAt"` and moves the session's timeline row and HIGH_LEVEL counters to the new time — no session/play row moves, no locator. The first-item product rule (row comparison on `firstSourceId/firstSource`) is unchanged. | Proposal 2. |

---

### Task 1: Renames, ingest cursor, TransactionType enum

- [ ] Rename projection tables to the `wqPrj` prefix in `V1__baseline.sql` and every SQL string, test and fixture (`wqWebItem`→`wqPrjWebItem` etc. — still two tables in this task; `wqWebHighLevel`→`wqPrjWebHighLevel`, `wqCcHighLevel`→`wqPrjCcHighLevel`, counters `wqPrjCountMonth`/`wqPrjCountDay`, `wqPrjSession`, `wqPrjPlay`, `wqPrjGroupLocator`, `wqPrjItemLocator`, `wqPrjPendingCancel`); update `PartitionManager`'s table list and `ViewSourceIsolationTest` patterns (`wqPrjCc`/`wqPrjWeb`, case-insensitive).
- [ ] Merge `wqStreamOffset` + `wqIngestWatermark` into `"wqIngestCursor"` per the final schema; port `StreamOffsetStore` and `DeltaWatermarkStore` (keep the delta watermark's `greatest` monotonicity and MANDATORY propagation).
- [ ] Replace `wqTxType` + `TxTypeRegistry` with the D1 enum; `type` columns become `text`; counters key `"type" text` with `''` = ALL; rollup direction from the enum; mappers and response `transactionType` read the stored code; D2.
- [ ] Tests: update names; replace registry tests with enum tests (`directionOf` for PLAY/WIN/NEUTRAL/unknown); a test that an unknown code is stored and NEUTRAL; full suite green. Commit `refactor(db): wqPrj prefix, one ingest cursor, TransactionType enum`.

### Task 2: One item table with views; one timeline table

- [ ] Schema: `"wqPrjItem"` replaces the two item tables; views `"wqPrjWebItem"` / `"wqPrjCcItem"` per the final schema; `"wqPrjTimeline"` replaces the two high-level tables, with `"onWeb"`; indexes recreated on the single tables (the V12 session-member partial index, the covering indexes).
- [ ] Write side: insert each item once; timeline row once with `onWeb = source ∈ web sources`; cancellation and versioned apply update the single row; counters unchanged per view (viewKind still WEB and CC — an item feeding both views still counts under both).
- [ ] Read side: `/web` queries read `"wqPrjWebItem"` and `"wqPrjTimeline" where "onWeb"`; `/cc` reads `"wqPrjCcItem"` and all timeline rows. EXPLAIN tests updated to the single tables and still assert index use/pruning through the view.
- [ ] Tests: oracles (`ProjectionInvariants`, `ProjectionSnapshot`, `NaiveOffsetOracle`) ported to the single tables; a test that a payment is never visible through `"wqPrjWebItem"` nor in `/web` timeline paging; full suite green. Commit `refactor(db): one item table with per-endpoint views; one timeline`.

### Task 3: Sessions and plays unpartitioned; no group locator

- [ ] Schema per the final table: drop `wqPrjGroupLocator`; `"wqPrjSession"` key `("userId","batchId")`, `"wqPrjPlay"` key `("userId","batchId","subBatchId")`, not partitioned; reading index on `("userId","startedAt" desc,"batchId" desc)`.
- [ ] `RollupUpdater` per D5: upsert by batch key, lock order play row then session row (`for update`), re-anchor = update `startedAt` + move timeline row and HIGH_LEVEL counters; delete the row-move/re-key code. `PartitionManager` no longer manages session/play partitions.
- [ ] Read-side assembly fetches sessions/plays by `(userId, batchId)` keys.
- [ ] Tests: re-anchor test (late earlier item moves startedAt and the timeline row, totals unchanged), first-item product tests, P-1/P-4/P-8 example tests and the stream/poller concurrency test green. Commit `refactor(projection): unpartitioned sessions keyed by batch; no group locator`.

### Task 4: One per-day counter table

- [ ] Schema: `"wqPrjCount"` per the final table replaces month+day.
- [ ] `CounterUpdater` writes day rows only; `CounterReader` and `RangeDecomposition` per D3 (DAY + LIVE); the offset walk over days.
- [ ] Tests: totals/offset/paging example tests (level 0 and high level) and counter-consistency oracle green; a multi-year range test (counters summed over ≥ 1000 days equals live COUNT). Commit `refactor(read): per-day counters only`.

### Task 5: Key query bounded by the page's own days

- [ ] D4 in the shared walk for both page units.
- [ ] Tests: correctness unchanged (paging oracle tests); an EXPLAIN test for a 5-year range where the page's rows sit in one month asserts the key query scans only that month's partition(s) (count partitions in the plan); show RED against the `from` lower bound. Commit `perf(read): bound the key query by the page's own day range`.

### Task 6 (controller): spec, diagrams, memory

- [ ] Spec §5 (tables), §7 (read path), §2.2 rule names, §8.5 scan rule; table-map and flow artifacts republished with the final names; memory.

## Execution notes

(Rulings recorded during execution go here.)
