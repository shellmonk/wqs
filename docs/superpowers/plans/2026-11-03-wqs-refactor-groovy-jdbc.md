# WQS Refactor — Groovy Gradle DSL and jOOQ → Spring JdbcClient

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Replace the Kotlin Gradle DSL with Groovy build scripts, and replace jOOQ (DSL + code generation) with hand-written SQL through Spring's `JdbcClient`, with **no behaviour change**: the existing test suite (546 tests) stays green throughout and is the safety net.

**Architecture:** Port incrementally. Spring's jOOQ integration and `JdbcClient` share one `DataSourceTransactionManager` per pool (both resolve connections through `DataSourceUtils`), so jOOQ and JdbcClient code can coexist in one transaction while classes are ported one package at a time. jOOQ, its code generation and its ArchUnit rules are removed only in the last task.

**Tech Stack:** Gradle (Groovy DSL), Java 21, Spring Boot 3.4.1 (`spring-boot-starter-jdbc`, `JdbcClient`), Flyway, Postgres 16, ArchUnit, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-31-wallet-query-service-design.md` — §2 (jOOQ rules → replaced), §8.3 (codegen → removed), §8.5 (ArchUnit), §10 (reader/writer split, snapshot rule). User decisions 2026-11-03: Groovy DSL; Spring JdbcClient.

## Global Constraints

- **No behaviour change.** Every query must produce the same rows, order, types and locking as before (same `for update`, `on conflict`, `greatest`, `unnest` joins, partition-pruning predicates on `"occurredAt"`/`"period"`). SQL text is copied from what jOOQ renders where it is not obvious — use the existing tests and, where useful, jOOQ's `getSQL()` before deleting a query.
- camelCase identifiers are **double-quoted** in every SQL string (Postgres folds unquoted names to lower case).
- **Reader/writer split and snapshot rule stay:** every read path runs in ONE read-only REPEATABLE READ transaction on the reader pool; writes use the writer pool (`@Primary`). Replace `@ReaderDsl DSLContext` with `@ReaderJdbc JdbcClient` plus a reader `TransactionTemplate` (`@ReaderTx`), configured exactly like today's reader transactions (read-only, REPEATABLE READ).
- Types: `timestamptz` ↔ `Instant` via `OffsetDateTime` (`rs.getObject(col, OffsetDateTime.class)`) and bind `OffsetDateTime` in UTC; `numeric` ↔ `BigDecimal` (scale preserved); `jsonb` bound as `PGobject`/`cast(? as jsonb)` and read as `String`; arrays bound with `connection.createArrayOf` or `cast(? as bigint[])`/`text[]`.
- Batch-sized `IN`/`unnest` lists keep using arrays, not string concatenation. **Never** concatenate request or event values into SQL — always bind parameters.
- Test policy (user): jqwik property tests are opt-in and **never run**; deterministic tests only. Each task ends with `./gradlew clean build` green.
- ArchUnit rules are replaced, not weakened (Task 5).

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | buildSrc's Kotlin sources (`MigrationQuotingCheck.kt` and its test) are rewritten in **Java**; all `*.gradle.kts` become `*.gradle`; the version catalog `gradle/libs.versions.toml` stays. | "Regular Gradle files" = Groovy DSL; no Kotlin left in the build. |
| D2 | One small SQL-owning class per existing jOOQ class (same name, same package, same public API); SQL as Java text blocks in the class; row mapping via private `RowMapper` lambdas or records. | Smallest diff; callers unchanged. |
| D3 | `JdbcClient` beans: writer = the `@Primary` one (Boot's auto-configured `JdbcClient` over the primary DataSource); reader = `@ReaderJdbc JdbcClient` over the reader DataSource, plus `@ReaderTx TransactionTemplate` (read-only, REPEATABLE READ) replacing `readerDsl.transactionResult`. | Keeps phase-4 D11 semantics. |
| D4 | The `db` module keeps Flyway migrations and `PartitionManager`-level SQL helpers; the jOOQ codegen source set, generator strategy and `wqs.db.generated` are deleted. `@RawSqlJustified` is deleted. | No generated code left. |
| D5 | New ArchUnit rules (each shown able to fail): (a) `wqs.api..` and `wqs.domain..` do not depend on `org.springframework.jdbc..` or `java.sql..`; (b) every `JdbcClient` injected into `wqs.read..` is `@ReaderJdbc`; (c) no class depends on `org.jooq..` (guards re-introduction). Removed: the jOOQ plain-SQL rule and the DSLContext rules. | Same intents as §8.5 minus jOOQ. |

---

### Task 1: Gradle Kotlin DSL → Groovy DSL

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `db/build.gradle.kts`, `buildSrc/build.gradle.kts` → `.gradle`; `buildSrc/src/main/kotlin/MigrationQuotingCheck.kt` → `buildSrc/src/main/java/…`; its test → Java.

- [ ] Translate each script 1:1 (plugins, version catalog accessors, `excludeTags("property")`, the `propertyTest` task with its 60-minute timeout, the broker tag handling, the jOOQ codegen task and its inputs/outputs, `checkMigrationQuoting` wiring, compiler args incl. MapStruct `unmappedTargetPolicy=ERROR`).
- [ ] Verify: `./gradlew clean build` green with the same test count (546) and no property tests run; `./gradlew tasks --all` lists `propertyTest`, `checkMigrationQuoting`, the codegen task; `find . -name '*.kts' -o -name '*.kt' | grep -v build/` is empty.
- [ ] Commit `build: Groovy DSL instead of Kotlin DSL; buildSrc in Java`.

### Task 2: JdbcClient infrastructure + `wqs.db`, `wqs.ingest`, `wqs.config`

**Files:** `wqs/config/DataSourceConfig.java`, new `ReaderJdbc.java`, `ReaderTx.java`; `wqs/db/TxTypeRegistry.java`, `wqs/db/PartitionManager.java` (and any jOOQ use in `wqs.db`); `wqs/ingest/DeadLetterStore.java`, `DeadLetterMetrics.java`, `ingest/stream/StreamOffsetStore.java`; their tests.

- [ ] Add `spring-boot-starter-jdbc` (Boot already brings `JdbcClient` auto-config). Define the reader `JdbcClient`/`TransactionTemplate` per D3; keep the jOOQ beans until Task 5.
- [ ] Port each listed class to `JdbcClient` per D2 and the Global Constraints. `TxTypeRegistry` keeps its REQUIRES_NEW insert semantics (use a writer `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`) and its reader-tx overloads (take a `JdbcClient`).
- [ ] Run the tests covering these classes, then `./gradlew clean build`. Commit `refactor(db,ingest): JdbcClient instead of jOOQ`.

### Task 3: Port `wqs.projection`

**Files:** `ItemInserter`, `CounterUpdater`, `RollupUpdater`, `HighLevelUpdater`, `CancellationApplier`, `PendingCancelStore`, `VersionedApplier`, `DeltaWatermarkStore`, `IngestRules`, and any other jOOQ user in `wqs.projection`.

- [ ] Port per D2. Preserve: lock order (play locator before session locator, documented in `RollupUpdater`), every `for update`, `on conflict … do update` expressions incl. the row-comparison tie-break for the first item, strict counter decrements (missing row ⇒ failure, not clamp), `greatest` watermark, `REQUIRES_NEW`/`MANDATORY` propagation.
- [ ] Run the projection, cancellation, rollup, counter, high-level, versioned-apply, writer and ingest tests (all example tests), then `./gradlew clean build`. Commit `refactor(projection): JdbcClient instead of jOOQ`.

### Task 4: Port `wqs.read`

**Files:** `CounterReader`, `Level0Query`, `HighLevelQuery`, `HighLevelItemQuery`, `HighLevelAssembler`, `ProjectionReader`, `read/web/*`, `read/cc/*`.

- [ ] Port per D2/D3. Keep one query class per (view, level); the snapshot rule (total, walk and assembly in ONE `@ReaderTx` transaction); the `unnest` key fetches; the V10/V12 index-friendly predicates (the EXPLAIN tests must stay green unchanged); statement-count budgets (the jOOQ `ExecuteListener` used by the count test is replaced by an equivalent JDBC-level counter — e.g. a `DataSource` proxy or a `StatementInspector`-style wrapper in the test — with the same budget assertions).
- [ ] Run the read-path tests (paging, totals, offset walk, assembly, EXPLAIN, API), then `./gradlew clean build`. Commit `refactor(read): JdbcClient instead of jOOQ`.

### Task 5: Remove jOOQ; tests; ArchUnit; docs

- [ ] Port every remaining test helper that uses jOOQ or generated tables (`PostgresTestBase`, `ProjectionInvariants`, `ProjectionSnapshot`, oracles, generators, fixtures) to `JdbcClient`/plain JDBC — including the opt-in property-test sources so they still **compile** (they are not run).
- [ ] Delete jOOQ dependencies, the codegen source set/task/strategy, `wqs.db.generated`, `@RawSqlJustified`, `ReaderDsl`, jOOQ beans and config; remove jOOQ from the version catalog.
- [ ] Replace ArchUnit rules per D5; show each new rule rejects a fixture.
- [ ] `grep -rn 'jooq' --include='*.java' --include='*.gradle' --include='*.toml' --include='*.yaml' .` (excluding build/) is empty; `./gradlew clean build` green.
- [ ] Commit `refactor: remove jOOQ`.
- [ ] (Controller) Spec §2/§8.3/§8.5/§10 and memory updated.

## Execution notes

Executed 2026-11-03 with subagent-driven development on main; reviews of tasks N and implementation of N+1 overlapped to save time.

### Rulings

- R1 Ruling: T2 adds a test proving jOOQ and JdbcClient writes in one @Transactional share one connection/tx (rollback undoes both) — guards incremental porting — cost if wrong: mixed-tx partial commits during T3/T4
- Ruling: Task 3 dispatched in parallel with Task 2 review (time) — T3 builds on T2 infra; a T2 infra finding would be carried into T3 — cost if wrong: T3 rework
- Ruling: Task 5 task gate folded into the final whole-branch review (time) — cost if wrong: none extra

### Deferred minors

- Task 1: minor (deferred): buildSrc junit-platform-launcher unversioned; junit-jupiter version hard-coded; Files.readString strict on malformed UTF-8
- Task 2: minor (deferred): MigrationTest builds a second PGSimpleDataSource needlessly
- Task 4: minor (deferred): Level0 fetch maps inside streaming RowCallbackHandler (codeOf on same connection with open ResultSet; fine at default fetch size); StatementRecorder wraps prepareCall as plain PreparedStatement; unsessioned binds timestamps as ISO text cast to timestamptz[] (as jOOQ did)
- Final: minor (deferred): D5b covers only JdbcClient — a wqs.read class injecting the writer DataSource/JdbcTemplate/NamedParameterJdbcTemplate bypasses routing (none today)
- Final: minor (deferred): T1 hard-coded junit version in buildSrc; T4 codeOf on open ResultSet safe only while no fetch size is set on the reader

