# WQS phases 1–3 — controller rulings

Decisions made on the user's behalf while executing `2026-11-02-wqs-phases-1-3.md` with subagent-driven development.
Each states what was decided, why, and what it costs if wrong. Extracted verbatim from the execution ledger, in order.
Plan-amending rulings exist because the plan was written without a compiler; the spec was the binding authority.

- Ruling R1: Task 1 must APPEND build/, .gradle/ etc to the existing .gitignore and preserve the
  `.superpowers/` entry, not overwrite the file. Cost if wrong: SDD scratch gets committed to main.
- Ruling R2: AsInDatabaseStrategy overrides getJavaIdentifier for COLUMN definitions only, delegating
  to super for tables/schemas. Reason: the plan's own code reads `Tables.WQ_WEB_ITEM` and
  `WQ_WEB_ITEM.occurredAt`, and spec 8.3's rationale is column readability plus MapStruct
  accessors — table constants were never the point. Overriding for all definitions would rename
  the table constant to `wqWebItem` and break Tasks 12, 13, 17 and 21. Task 4 also gains an
  assertion that `Tables.WQ_WEB_ITEM` resolves. Cost if wrong: mixed naming convention in
  generated code; cheap to flip later since it is one strategy method.
- Ruling R3: Sequence access must be quoted — `dsl.nextval(DSL.sequence(DSL.name("wqItemIdSeq")))`,
  never `dsl.nextval("wqItemIdSeq")`. Reason: Postgres folds the unquoted form to `wqitemidseq`
  and fails at runtime. Cost if wrong: Task 12 fails immediately and visibly.
- Ruling R4: Task 16's StreamConsumer constructor takes 7 parameters and has no DeadLetterStore
  field or import; Task 16's test drops the trailing `null`. Task 17 adds the 8th parameter, the
  field, the deadLetteredCounter, and updates Task 16's test construction. Reason: as written,
  Task 16 imports a class Task 17 creates, so Task 16 cannot compile. Cost if wrong: one extra
  constructor edit in Task 17.
- Ruling R5: Job definition beans move to `wqs.jobs` (e.g. `wqs.jobs.JobDefinitions`); the Clock
  bean stays in `wqs.config.ClockConfig`. Reason: Task 9's rule "nothing outside wqs.jobs depends
  on wqs.jobs" is violated by a `wqs.config.JobBeans` that references PartitionRolloverJob and
  JobDefinition. Spec section 10 wants jobs to be entry points, so the wiring belongs with them.
  Cost if wrong: a config class sits in the jobs package.
- Ruling R6: Task 22 extends ProjectionSnapshot to cover wqSession, wqPlay, both high-level tables
  and both counter tables, excluding volatile columns. Reason: the plan claims P-1 "now exercises
  rollups, the page index and counters" after Task 22, but the snapshot only compares items and
  the locator, so convergence of the new state would go untested. Cost if wrong: P-1 runtime grows
  modestly.
- Ruling R7: Gradle wiring of the `codegen` source set's dependency configuration is at the
  implementer's discretion (string notation is fine) provided `:db:generateJooq` behaves and is
  UP-TO-DATE on a second run. Reason: the plan's `by configurations.getting` snippet is not
  callable as a function. Cost if wrong: none behavioural.
- Ruling R8: PendingCancelStore.drainFor may use an `in (...)` list instead of `= any(?)` if array
  binding misbehaves. Reason: array binding through jOOQ's plain-SQL path is the one uncertain
  call in Task 23. Cost if wrong: none; both are correct SQL.
- Ruling R9: Task 1 does NOT wire `compileJava dependsOn generateJooq` nor add the generated
  source dir. Task 4 adds BOTH, alongside creating CodegenRunner and AsInDatabaseStrategy.
  Reason: the plan put the dependency edge in Task 1 and the class it needs in Task 4 — a
  genuine ordering defect. Rejected alternatives: a throwaway stub (pollutes main and Task 4
  then deletes it), deferring Task 1's green (breaks the TDD gate and leaves main un-tested),
  pulling Task 2's migrations forward (merges tasks and inflates scope). Nothing between
  Tasks 1 and 4 needs generated classes: Task 2's tests use plain SQL via DSL.using, and
  Task 3 is a Gradle/Kotlin check with no Java. Cost if wrong: Task 4 grows by two small
  build-file edits; no behavioural change to the final state.
- Ruling R10: Approved the implementer's own deviation — `db/build.gradle.kts` uses the
  `java-library` plugin instead of `java`, because `api(libs.jooq)` requires it and `app`
  needs jOOQ transitively. Correct and necessary. Cost if wrong: none.
- Ruling R11: FIX the plan-mandated Important finding now rather than deferring it — add
  `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` to app/build.gradle.kts.
  Weighed against the plan text (the brief's Step 6 omits it verbatim, so the plan mandates the
  defect): the spec is silent on test-output hygiene, so the review rubric governs, and the
  plan's omission reads as oversight rather than decision. Decisive factor: the warning would
  repeat in every test run for the 23 remaining tasks, which destroys "output pristine" as a
  usable quality signal for the whole run. One line now beats 23 tasks of noise.
  Cost if wrong: one extra test-runtime dependency, no behavioural effect.
- Ruling R12: Also addressing the Minor (ProcessorChainTest's sensitivity is asserted, not
  demonstrated) opportunistically in the same round, since a round is already running. The test
  exists solely to catch a silently-compiles-but-drops-data bug, so proving it goes RED when
  lombok-mapstruct-binding is removed is cheap insurance. Not a loop extension: if the
  demonstration fails to materialise it does not block the task. Cost if wrong: a few minutes.
- Ruling R13, three parts:
  (a) KEEP lombok-mapstruct-binding. Spec section 2.4 mandates it, it costs nothing, and it is
  insurance against toolchain drift — the documented failure mode is real in general even if
  it does not reproduce on this exact combination. Removing provably-unnecessary insurance
  against a silent data-loss bug is a bad trade.
  (b) FIX the test's javadoc, which currently asserts "without the binding MapStruct cannot see
  Lombok-generated accessors and silently maps null." We have now measured that claim to be
  false here. A comment that lies is worse than no comment, because the next reader trusts it
  and believes the regression is covered. The test stays as a positive-case integration check
  and must say so.
  (c) Do NOT chase a stronger negative control. You cannot write a test for a condition you
  cannot induce, and manufacturing one (fluent accessors, builder-only sources) to make the
  guard bite would be scope creep well outside Task 1.
  Cost if wrong: the binding is one unused dependency, and the test is honestly labelled rather
  than falsely reassuring. Both cheap to revisit.
- Ruling R14: APPROVED the implementer's addition of `testImplementation(libs.postgresql)` to
  app/build.gradle.kts. Genuine plan defect: the plan declared the driver `runtimeOnly`, but the
  plan's own PostgresTestBase constructs PGSimpleDataSource directly, which needs it at compile
  time on the test classpath. Minimal and uses the existing catalog entry. Cost if wrong: none.
- Ruling R15: the fix must be IN THE REPO and the home-directory file must then be removed.
  Reasons: (1) an undocumented file in one developer's home directory is invisible to teammates
  and to CI, which hit the identical wall with no hint; (2) it is a change to the user's machine
  outside the project that I did not authorise and must not depend on; (3) pinning to 1.44 pins
  to this engine's minimum rather than negotiating, so it is fragile on a different host.
  Preferred fix: bump the Testcontainers version in gradle/libs.versions.toml to a release whose
  bundled docker-java negotiates modern API versions, and verify green with the home file absent.
  Fallback if the bump does not resolve it: commit an explicit api.version system property on the
  Gradle test task, with a comment naming the engine-minimum reason. Either way exactly ONE
  mechanism survives, and it lives in the repo.
  Sequence matters: verify the repo fix works BEFORE deleting the home file, or the build breaks
  mid-run. Deleting it afterwards restores this machine to its pre-run state.
  Cost if wrong: a Testcontainers bump could surface unrelated API changes; the fallback is a
  one-line pin that is easy to revert.
- Ruling R16: remove the ineffective container-reuse request. `.withReuse(true)` in
  PostgresTestBase plus `systemProperty("testcontainers.reuse.enable", "true")` in the root
  build are both dead: Testcontainers reads the reuse opt-in only from the user-level
  properties file (deliberately, so a project cannot force reuse onto a CI machine), so the
  system property cannot enable it and the request only emits a WARN on every run. Same
  reasoning as R11: noise repeated across ~12 remaining DB tasks destroys "output pristine" as
  a signal. Rejected alternative: writing the opt-in into the user's home file — that is exactly
  the home-directory dependency R15 forbids, and it would mean editing a pre-existing user file.
  Cost if wrong: a developer who later opts into reuse loses the speed-up until they re-add the
  call; a comment will point them at it.
- Ruling R17: fold a two-line .gitignore hygiene addition (app/.jqwik-database, buildSrc/.kotlin/)
  into Task 4's dispatch as explicitly controller-added scope. Reason: this run commits directly
  to main across 24 tasks, so untracked-artifact paths accumulate risk, and the only current
  protection is implementers staging explicit paths. Two lines now removes a whole class of
  accidental commit. Labeled as added scope in the dispatch so the reviewer is not surprised by
  files outside the brief. Cost if wrong: two ignore entries to delete.
- Ruling R18: keep the broad column coverage, but replace the layered-regex stripping with a
  SINGLE-PASS STATE-MACHINE scan. Reasoning: narrowing back to the spec's literal wq[A-Z] would
  kill the false positives but give up the higher-frequency risk — V2 alone has ~16 camelCase
  columns per table versus 2 table names, so unquoted columns are the likelier defect and the
  broadening earns its keep. But layered regexes provably cannot resolve the interactions: a
  `--` inside a string literal is not a comment, and a `'` inside a comment does not open a
  string, so any ordering of independent regexes is wrong for one of those cases (the reviewer
  found exactly this as Minor 5). A ~30-line scanner tracking line-comment / block-comment /
  single-quote / dollar-quote / double-quote state is correct rather than approximately correct,
  and this check gates a hard spec constraint for every future migration.
  Cost if wrong: a slightly longer function, fully unit-tested.
- Ruling R19: DROP SQL_KEYWORDS / "isClustered" entirely. Dead code, no test can reach it, and its
  comment mischaracterises it. A real exemption can be added later with a test proving it needed.
  Cost if wrong: none.
- Ruling R20: fix the `item2Id` detection miss while the detector is being rewritten. Tokenise
  identifier-shaped runs and test each token for a lowercase-to-uppercase transition, rather than
  relying on \b adjacency. Cheap to fold in now, and it removes a real false negative.
  Cost if wrong: none; it strictly widens detection.
- Ruling R21: both of the above stay DEFERRED rather than opening a third fix round. Weighed:
  neither is load-bearing for a later task — none of the planned migrations (V3 Quartz, V5-V8)
  contain nested comments, and Quartz's vendored DDL is UPPER_SNAKE and lowercase throughout,
  which isCamelCase correctly never flags since UPPER_SNAKE never sets sawLowercase. Both risks
  are therefore low, and a third round on low-risk polish is poor allocation with 21 tasks left.
  Mitigation instead of a fix: Task 6's dispatch will carry an explicit watch note, so if the
  vendored Quartz DDL trips checkMigrationQuoting, that is the signal to fix immediately with
  real evidence in hand rather than speculatively now.
  Cost if wrong: one confusing build failure in a later task, diagnosable from this ledger entry.
- Ruling R22: extend AsInDatabaseStrategy to preserve camelCase humps across all four surfaces,
  rather than rewriting four later tasks to match jOOQ's flattened defaults.
  - getJavaClassName: PascalCase of the DB name (capitalise first char, preserve the rest),
  plus jOOQ's mode suffix -> WqWebItem, WqWebItemRecord
  - getJavaIdentifier, columns: DB name verbatim (unchanged from R2) -> occurredAt
  - getJavaIdentifier, non-columns: UPPER_SNAKE derived by inserting '_' before each hump
  -> WQ_WEB_ITEM, matching every reference the plan already contains
  - getJavaGetterName / getJavaSetterName: "get"/"set" + capitalised-first-char DB name,
  preserving interior humps -> getOccurredAt. NOTE this is an override the plan forbade,
  but the plan forbade it for a different and now-superseded reason: jOOQ's documented
  example yields getoccurredAt (lowercase first letter, not a JavaBean accessor). Producing
  getOccurredAt IS a proper accessor, so MapStruct works and the original hazard is avoided.
  Rejected alternative: accept jOOQ's defaults and rewrite Tasks 12/13/14/17/21 to use
  Tables.WQWEBITEM / Wqwebitem / getUserid. Less strategy code, but unreadable generated code,
  and it forces a per-field @Mapping in Task 14 for every column whose name has a hump.
  Cost if wrong: ~40 lines of pure string transformation, unit-testable, and reversible by
  deleting overrides. Spec 8.3 must be updated to describe the real behaviour.
- Ruling R23: APPROVED the implementer's deviation adding `doFirst { outputDir.deleteRecursively() }`
  to generateJooq. It found a SILENT failure specific to this change: on case-insensitive APFS,
  regenerating over prior output makes jOOQ write WqWebItem.java over Wqwebitem.java while the
  directory entry keeps the OLD spelling, and jOOQ's own stale sweep then deletes it — yielding
  a GREEN build with zero table classes. The Wqwebitem -> WqWebItem change is case-only, which
  is exactly the shape macOS mishandles, so this bug was created by R22 itself. Verified not to
  break incrementality: doFirst only runs when the task executes, and run 2 is still UP-TO-DATE.
  Cost if wrong: codegen always regenerates fully when it runs, which it already did.
- Ruling R24: REVERSING my earlier deferral of the codegen-classpath input. The reviewer verified
  in Gradle 8.12 bytecode that JavaExec.getClasspath() carries NO @Classpath or @InputFiles
  annotation, so the jOOQ jars are genuinely untracked: a jooq version bump would leave
  generateJooq UP-TO-DATE and compile last version's generated source against the new runtime.
  That is the SAME silent-green-build shape as the APFS bug R23 just fixed, and this task's
  whole correctness story rests on its declared inputs. I previously deferred this as
  hypothetical; one line, a silent failure class we have now hit twice in this one task, and an
  Important verdict from a reviewer that earned credibility by disassembly, together outweigh
  "no bump is planned". Fix: inputs.files(sourceSets["codegen"].runtimeClasspath), which also
  subsumes the existing inputs.dir("src/codegen/java").
  Cost if wrong: generateJooq re-runs when any codegen dependency changes, which is correct.
- Ruling R25: also fixing Minor 1 — `doFirst { outputDir.deleteRecursively() }` discards its
  boolean result, and Kotlin's deleteRecursively swallows IOException and returns false. A
  partial delete would then let generation proceed into a half-cleaned tree, reproducing exactly
  the silent-green-build mode R23 exists to prevent. A hole in R23's own fix, so it goes in the
  same round. Cost if wrong: none.
- Ruling R26: also fixing Minor 6 — GeneratedCodeTest asserts the getter but never the setter,
  though the strategy overrides getJavaSetterName and Task 14 writes into records under
  unmappedTargetPolicy = ERROR; and WQ_ITEM_LOCATOR.sourceId (Task 13's reference) is confirmed
  only in prose. Two assertions protecting two later tasks. Cost if wrong: none.
- Ruling R27 (Important, fix now): partitionExists does a bare `relname = ?` lookup in pg_class
  with no schema qualification and no pg_inherits join proving the relation is attached to the
  intended parent. A false "exists" makes createPartitionIfAbsent SKIP creating the real
  partition — precisely the silent-gap failure this class exists to prevent. The reviewer's
  sharpest point: the TEST's own existingPartitionNames() helper joins pg_inherits and is
  therefore more rigorous than the production check it exercises. Task 6 adds the `quartz`
  schema next, so the ambiguity becomes live immediately. Fix: qualify by schema and verify via
  pg_inherits.inhparent against the parent's oid.
  Cost if wrong: a slightly longer catalogue query on a path that runs a handful of times a day.
- Ruling R28 (controller-resolved warning, fix now): partition bounds are built from bare
  LocalDate literals ('2031-01-01'), which Postgres resolves against a timestamptz column using
  the session TimeZone GUC at DDL time. I cannot verify the ambient setting from the diff and I
  am declining to depend on it: the Testcontainers image and production Aurora need not agree,
  and the class does not control either. Consequence if the GUC is not UTC is not a gap —
  partitions still tile — but bounds would be offset from UTC calendar months, so a query over
  a UTC month would touch TWO partitions instead of one, quietly degrading the partition
  pruning the whole read design depends on (spec 7.1 mandates UTC throughout, and counters are
  keyed by UTC periods in Java). Fix: state the instant explicitly rather than relying on config.
  Cost if wrong: none; an explicit instant is correct under any session timezone.
- Ruling R29 (Minor, fix now): runwayMonths has no defensive iteration cap. Task 8 calls it on a
  schedule as a gauge, and each iteration is a database round trip, so unexpected DB state would
  turn a metric scrape into hundreds of sequential queries. One-line guard.
- Ruling R30 (Minor, fix now): no retained test pins the half-open boundary instant. The
  implementer verified it with a temporary, reverted test; the property deserves a committed
  regression test, because a gap at a month boundary is silent. Adding a test deviates from the
  brief's verbatim test file, which I am authorising.
- Ruling R31: fix dropTestPartitions NOW — main must not stay red, and every later task runs this
  suite. Authorising Task 6's implementer to modify Task 5's test file: cross-task scope granted
  because Task 6 exposed it and holds the diagnosis plus an A/B harness. Note the shape: R27
  fixed exactly this class of bug in PRODUCTION (bare catalogue name match, no parentage check)
  and the test helper has the analogous defect — so the fix should verify parentage and filter
  relkind the same way, not merely add a relkind filter.
  Cost if wrong: a test-only helper; blast radius is the test suite.
- Ruling R32: add a permanent Spring-context test for the scheduler. The implementer proved the
  brief's Step 6 verification is HOLLOW — it claimed a tablePrefix typo would surface there, so
  the implementer deliberately broke tablePrefix and confirmed the designated command still
  reported BUILD SUCCESSFUL, because QuartzSchemaTest only does raw JDBC through
  PostgresTestBase and never boots a context. A verification step that cannot fail is worse than
  none: it manufactures false confidence. The implementer further found a typo surfaces as
  repeating background ERROR logs rather than a synchronous failure, so a bare "context loads"
  assertion would ALSO pass. The test must therefore perform a real JobStore round trip.
  Tasks 7 and 8 both depend on the scheduler actually working. Authorising the deviation from
  the brief's three-file list. Cost if wrong: one test that boots a context.
- Ruling R33 (PLAN DEFECT found during Task 7 dispatch prep, before any code): JobRegistrar's
  hasChanged, as the plan writes it, SILENTLY IGNORES interval changes. For a cron spec it
  compares expressions; for an interval spec it returns `existing instanceof CronTrigger`, which
  is false when the stored trigger is already a SimpleTrigger — so changing intervalSeconds from
  5 to 10 never reschedules. The plan's own comment claims "interval changes are handled by
  always rescheduling non-cron triggers below", which the code does not do, and the plan's seven
  tests only exercise a CRON change, so nothing would catch it. This defeats the component's
  entire reason for existing — a persistent JobStore ignoring config changes is exactly the
  confusing failure JobRegistrar was introduced to prevent — and the delta pollers in phase 6
  are the intervalSeconds users. Required: detect interval changes and add a test for them.
  Cost if wrong: one extra comparison and one test.
- Ruling R34: fix misfirePolicy change detection. My R33 was too narrowly scoped — I named
  intervalSeconds and fixed that instance instead of the class. hasChanged compares the cron
  string and the repeat interval only, so flipping misfirePolicy alone leaves the stored trigger
  with its stale misfire instruction. Concrete consequence, not theoretical: spec 6.5 mandates
  do-nothing for pollers precisely because a poller catches up via its own watermark and queued
  catch-up fires produce redundant polls. Someone switching a poller to FIRE_AND_PROCEED, or
  back, would get no effect and no indication why — and after downtime, a thundering herd.
  Guidance given: stop enumerating fields. Compare the STORED trigger against a freshly built
  DESIRED trigger on schedule plus misfire instruction, so any field buildTrigger consumes is
  automatically in scope. Two misses in two rounds is the argument against field-by-field.
  Cost if wrong: a slightly broader comparison that may reschedule marginally more often.
- Ruling R35: fix the misnamed orphan test. removes_an_orphan_whose_configuration_disappeared
  passes the same definitions list on both calls and only empties the config map, so it hits the
  spec == null branch in the main loop — the same path as the disabled test — and returns before
  removeOrphans ever runs. removeOrphans therefore has ZERO coverage while a test claims it. Same
  pattern as Task 6's hollow Step 6: a test named for coverage it does not provide is worse than
  no test, because it stops anyone looking. Cost if wrong: one corrected test.
- Ruling R36: the JobRegistrar javadoc OVERCLAIMS. It says any field added later is automatically
  in scope, but the comparison is still an enumerated set of getter comparisons, not a structural
  trigger diff — a future trigger-level property (priority, calendar name, end time) that
  buildTrigger started setting would still need its own branch. This is the THIRD comment in this
  run claiming more than its code delivers (after ProcessorChainTest in Task 1 and
  AsInDatabaseStrategy in Task 4), and it is the specific trap that produced R34: I believed a
  field was covered because a comment said so. Carrying the one-line correction into Task 8's
  dispatch rather than opening a round 2 for a comment — Task 8 touches wqs/jobs anyway.
  Cost if wrong: one sentence.
- Ruling R37: APPROVED the implementer's fix to the brief's order-dependent test. Plan defect: the
  brief's two tests share a fixed clock against the shared static Testcontainers Postgres with no
  cleanup, so partitions created by the first leak into the second. The implementer reproduced the
  nondeterministic failure before fixing it and added @BeforeEach cleanup, leaving assertions and
  intent unchanged. Cost if wrong: test-only.
- Ruling R38: APPROVED the implementer adding PartitionRolloverJobBootTest beyond the brief's file
  list. The brief's Step 7 verification was HOLLOW — it says to run the full suite and watch for
  NoSuchMethodException on the job's no-arg constructor, but the job's cron is 03:00 so the
  trigger never fires during a test run; absence of that exception proved nothing. The
  implementer verified Boot 3.4.1 wires SpringBeanJobFactory by inspecting QuartzAutoConfiguration
  bytecode, then forced scheduler.triggerJob to obtain real executed-behaviour proof.
  Cost if wrong: one Spring-context test.
- Ruling R39 (Important, fix now): add an explicit @Order to ensureCurrentMonthOnStartup. This
  task's central claim is that the current month exists before anything can write, but that holds
  today only because the only other ApplicationReadyEvent listener — JobRegistrar.reconcile —
  writes no partitioned data. Spring's multicast order for same-priority @EventListener beans is
  bean-registration order, not a documented guarantee. Task 16 adds a stream consumer on this
  same lifecycle event; registered earlier, it could insert before the partition exists and
  silently reopen the precise failure this task was built to close. A nondeterministic startup
  race is also among the hardest defects to diagnose, which makes the asymmetry stark: one
  annotation now versus an intermittent production insert failure later.
  Cost if wrong: an annotation that pins an ordering nothing else contests.
- Ruling R40 (Minor, fix now): tighten PartitionRolloverJobBootTest to assert an OBSERVABLE EFFECT.
  It currently proves construction well — dual JobListener/SchedulerListener latch, correctly
  anticipating that a JobFactory failure surfaces via schedulerError rather than jobWasExecuted —
  but it only asserts the job did not throw. A gutted execute() that stopped delegating would
  still pass. That is the SIXTH instance of this run's recurring shape, and I have ruled to fix it
  three times already; consistency and cheapness both favour fixing it.
  Cost if wrong: one extra assertion.
- Ruling R41: the brief's raw-SQL rule is HOLLOW for the codebase it governs — it only flags direct calls to
  org.jooq.impl.DSL.field/condition(String). PartitionManager (the one class it annotates) uses
  dsl.execute(String), .from("pg_inherits") and .where(String, ...) — none of which the rule sees. So the
  rule passes with or without @RawSqlJustified; the annotation would be decorative (EIGHTH absence-of-symptom
  instance). Spec 2.2 rule 1 prohibits DSL.field(String) AND .sql(...) — the whole plain-SQL API. Ruled:
  rewrite the condition to flag any call whose resolved target method is annotated @org.jooq.PlainSQL
  (verified: RUNTIME retention in jooq 3.19.16; jOOQ marks exactly its plain-SQL API with it). Covers
  DSL.field(String), DSL.sql, DSLContext.execute(String), where(String,...), etc. Rename test description accordingly.
  Cost if wrong: one ArchCondition; worst case over-flags something we then annotate.
- Ruling R42: exclude wqs.db.generated.. from the raw-SQL rule. Verified generated WqWebItem/WqCcItem contain
  plain-SQL calls (DSL.raw / DSL.field for nextval sequence defaults) — jOOQ-generated from the migrated schema,
  not hand-written, so outside the rule's intent. Without the exclusion, R41 would fail on generated code.
  Cost if wrong: a hand-written class placed in the generated package escapes the rule (unlikely; codegen owns it).
- Ruling R43: ArchUnit 1.3.0 defaults archRule.failOnEmptyShould=true, so the three rules whose that() targets
  packages that don't exist yet (wqs.api.., wqs.api.web.., wqs.read.web..) will FAIL "failed to check any classes",
  contradicting the brief's "pass trivially, deliberately". Ruled: .allowEmptyShould(true) on exactly those
  three rules, with a comment that they go live in phases 2-3. Not a global archunit.properties switch —
  that would also hide a typo'd package in future rules. Cost if wrong: none material.
- Ruling R44: strengthen Step 6's negative control. Two controls, both recorded with FAILING output:
  (a) brief's control (add a DSL.field("1", Integer.class) call to unannotated PartitionRolloverService — it has no
  DSLContext, so use the static DSL call alone); (b) remove @RawSqlJustified from PartitionManager → rule must FAIL
  naming PartitionManager. (b) is what proves the annotation is load-bearing. Revert both. Cost: two extra runs.
- Ruling R45 (supersedes R3's form): ItemInserter uses the generated typed constant
  `dsl.nextval(wqs.db.generated.Sequences.WQ_ITEM_ID_SEQ)` (verified present: Sequence<Long> for "wqItemIdSeq").
  The brief's dsl.nextval(String) is both the unquoted-folding bug R3 named AND a @PlainSQL call that Task 9's
  ArchUnit rule (correctly) rejects. R3's DSL.sequence(DSL.name(...)) also works but hand-builds what codegen already
  provides. Cost if wrong: none — same SQL, typed.
  Other checks: locator PK ("source","sourceId") makes onConflictDoNothing the gate as described; tests can fail
  (replay asserts false + counts); locator is not partitioned; item tables need the 2033-04 partition, which setUp creates.
- Ruling R46: DROP the brief's Step 6 (app/src/test/resources/application.yaml). Spring Boot loads classpath:application.yaml
  as a single resource — the test-classpath copy SHADOWS the main file entirely, it does not merge. Every Spring test would
  lose quartz job-store-type: jdbc (silently falling back to RAMJobStore — JobRegistrar's stored-trigger tests could then
  pass against the wrong store), the quartz.QRTZ_ tablePrefix/clustering, wqs.jobs.partition-rollover.params.monthsAhead
  (job fails), and actuator exposure. quartz.auto-startup=false would also break PartitionRolloverJobBootTest's triggerJob.
  Instead: ProjectionWriterTest adds registry.add("spring.flyway.enabled", () -> "false") in its own @DynamicPropertySource
  (same pattern as PartitionRolloverJobBootTest). wqs.stream.enabled does not exist yet — Task 16 decides its own default.
  Cost if wrong: Quartz runs during this context test, as it already does in other context tests.
  Other checks: rollback test discriminates via count("wqWebItem")==1 (tx-3 survives without @Transactional); its offset
  assertion alone cannot discriminate (offset write comes after the failing item) — acceptable, count carries it.
  Spring Boot's jOOQ autoconfig wraps the DataSource transaction-aware, so @Transactional covers DSLContext calls.
- Ruling R47: reviewer Minor 1 is LOAD-BEARING, so it is not merely deferred — insertIfAbsent outside a transaction can
  commit the locator gate then fail the view insert, after which every replay returns false: permanent silent loss.
  Carried into Task 13 (which owns the transaction): annotate ItemInserter.insertIfAbsent with
  @Transactional(propagation = MANDATORY) + a javadoc sentence. Direct-construction unit tests (no proxy) are unaffected;
  in the Spring context a bare call fails loudly. Task 13's rollback test covers the atomicity contract (reviewer minor 4).
  Cost if wrong: one annotation; a future non-transactional caller gets IllegalTransactionStateException instead of data loss.
- Ruling R48: reviewer Minors 2+3 carried into Task 13 as one added ItemInserterTest case — read back every column of both
  item tables against the item (catches e.g. amount/balanceAfter swap; asserts cancelled=false) and assert the locator's
  itemId equals the view rows' itemId. Cost: one test.
- Ruling R49: maps_structural_fields asserts only 6 of 13 fields. unmappedTargetPolicy=ERROR catches a MISSING mapping, not a
  WRONG one (e.g. an explicit @Mapping pointing balanceAfter at amount). Implementer extends it to assert every component
  (occurredAt, productId, type, currency, balanceAfter, cancels=null, detailJson=null) using distinct values.
  Same rationale as R48. Cost: a few assertions.
- Ruling R50 (Critical, plan defect): RabbitStreamSource is @Component and needs a com.rabbitmq.stream.Environment bean that NO
  task defines (raw stream-client, no Spring autoconfig). The moment Task 16 lands, EVERY @SpringBootTest context fails to start
  (StreamConsumerTest included). Ruled: in Task 16 RabbitStreamSource is a plain class (no @Component). Production wiring is
  a controller-added Task 17b (below). Cost if wrong: none — wiring moves, nothing is lost.
- Ruling R51 (plan gap vs Phase 2 exit criterion "transactions flowing from a RabbitMQ stream into the item tables"): no task
  constructs StreamConsumer, starts it, polls it on a timer, or stops it. Controller adds Task 17b AFTER Task 17 (17 changes the
  constructor): wqs.ingest.stream.StreamConfig, @ConditionalOnProperty(wqs.stream.enabled=true): Environment bean from a new
  StreamProperties `uri` component (default rabbitmq-stream://localhost:5552); RabbitStreamSource bean
  @ConditionalOnMissingBean(StreamSource); StreamConsumer bean; start on ApplicationReadyEvent with an @Order AFTER
  PartitionRolloverService.ensureCurrentMonthOnStartup (HIGHEST_PRECEDENCE — exactly the race R39 anticipated; NOT
  SmartLifecycle, which starts before ApplicationReadyEvent); poll on a single-thread scheduler every batchMaxWait; stop
  (drainNow + close) on shutdown. Main application.yaml gets wqs.stream.enabled: false (deployments set WQS_STREAM_ENABLED=true)
  so broker-less test contexts start. Test: a @SpringBootTest with enabled=true and a FakeStreamSource @TestConfiguration bean
  proving startup subscription + projection end-to-end without a broker.
  Cost if wrong: a deployment that forgets WQS_STREAM_ENABLED does not ingest (and has no lag gauge) — flag for final review.
- Ruling R52 (concurrency defect in brief's design): the RabbitMQ client calls onMessage on its dispatch thread while poll() runs
  on a timer thread; both mutate the non-thread-safe BatchAccumulator. StreamConsumer.onMessage/poll/stop become synchronized.
  Holding the lock across writer.apply also gives natural backpressure on the dispatch thread. Cost: none at <100 tps.
- Ruling R53 (hollow #10): RabbitStreamSourceSmokeTest never touches RabbitStreamSource — it tests the raw client. Also it calls
  execInContainer BEFORE start() (throws: container not running), REPLACES the HostConfig wholesale (drops Testcontainers' own
  settings), and builds two producers. Ruled: enable the plugin via withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stream]."),
  "/etc/rabbitmq/enabled_plugins") before start; modify cmd.getHostConfig() rather than replace it; one producer; subscribe
  through RabbitStreamSource and assert the handler receives a StreamPayload with the right offset from a real JSON body.
- Ruling R54: unenumerated wire fields must land in StreamPayload.extra (the whole O-13 strategy), but objectMapper.readValue into
  the record silently DROPS unknown fields (Boot's FAIL_ON_UNKNOWN_PROPERTIES=false). Ruled: RabbitStreamSource deserialises via
  a tree — known components by record-component name (O-13 assumption, documented), everything else into extra as text — and a
  broker-less unit test proves: ISO Instant, exact BigDecimal, unknown field -> extra, missing optional fields -> null.
- Ruling R55 (hollow #11): publishes_ingest_lag_and_event_counts asserts only gauge != null. With the fixed clock at WHEN+5s and
  occurredAt=WHEN it must assert value 5.0.
- Ruling R56: build.gradle.kts — REPLACE the existing tasks.withType<Test> block with the brief's version (keeps includeEngines,
  adds excludeTags); do not add a second block. R4 applies (7-param constructor, test drops trailing null).
- Ruling R57 (spec 6.4 violation in brief): after maxAttempts the brief dead-letters the WHOLE BATCH as ONE row with payload=null
  and sourceId=null, then advances the offset. With batchMaxSize 500, one poison event discards up to 499 good events
  unprojected, and the row retains nothing replayable (serialize(null) writes the JSON literal null, which passes NOT NULL).
  That is exactly "one malformed event stops history" in another form. Ruled: on batch failure after retries, fall back to
  applying the batch's items ONE AT A TIME (each a ProjectionBatch with committedOffset=null, own transaction); count
  projected/duplicates normally; dead-letter only items that fail individually — one row each, sourceId + source set, payload
  = the ProjectionItem serialised as JSON (it is a lossless superset of the wire fields we hold; documented). Then write the
  batch offset once. DeadLetterStore.record takes the item (signature change is ours to make; doc it).
  Cost if wrong: a systemic failure (e.g. a missing current partition) dead-letters every item individually instead of one
  batch row — still all replayable, and the deadlettered counter spikes. Flag for final review: alarm threshold.
- Ruling R58 (hollow #12): the_dead_letter_row_survives_the_rolled_back_projection_transaction calls record() OUTSIDE any
  transaction, where REQUIRES_NEW and REQUIRED behave identically — it cannot detect the defect its name promises. Ruled: call
  record() inside a TransactionTemplate whose outer transaction is marked rollback-only; assert the row survives. Negative
  control: temporarily switch to REQUIRED, confirm FAIL, revert.
- Ruling R59: add the discriminating consumer test for R57 — batch size 3, poison item in the middle: 2 items projected, exactly
  1 dead-letter row carrying tx-poison's sourceId, offset advanced to the batch's highest offset, deadlettered counter == 1.
- Ruling R60: StreamConsumer keeps R52's synchronized methods; R4's DeadLetterStore 8th constructor param arrives here and
  StreamConsumerTest's construction is updated accordingly (pass the autowired DeadLetterStore, not null).
- Ruling R61: RabbitStreamSource throws from inside the client's message handler on an undeserialisable body — the event is
  skipped and lost silently (spec 6.4's exact case; Task 17's dead-letter path never sees it, it only wraps writer.apply).
  Ruled into Task 17b: StreamHandler.onUndeserializable(body, offset, error) -> dead-letter row with payload
  {"rawBase64": ...}; does not advance the offset by itself. Cost if wrong: one interface method.
- Ruling R62 (fail-open silent loss — CONFIRMED real, and it SURVIVES the brief's Task 17): applyBatch runs after the batch is
  drained from the accumulator. If it ultimately throws (e.g. DB down: retries fail AND the dead-letter write fails), the
  items exist nowhere, and the next successful batch commits a HIGHER offset past them — silent permanent loss of financial
  events. Ruled into Task 17: on unrecoverable failure the consumer must not continue from memory — close the source, clear
  the accumulator, and re-subscribe from the stored offset + 1 (Postgres offset is authoritative), with a backoff before
  re-subscribing (driven from poll(), not a sleep on the dispatch thread). Also: a toItem() (mapper) failure in onMessage
  routes to the poison path, never escapes the handler. Test: a fake writer that fails N times then succeeds -> after recovery
  every event is projected exactly once and the offset is correct; with a failing dead-letter store, the stored offset never
  passes an unprojected event. Cost if wrong: re-delivery of an already-projected tail — harmless, the locator gate dedupes.
- Ruling R63 (unbounded BigDecimal exponent — VALID, moderate): "1e999999999" parses cheaply but any later toPlainString /
  setScale / plain serialisation can allocate gigabytes. Ruled into Task 17b (it modifies RabbitStreamSource): money fields
  (amount, balanceAfter) are validated against numeric(20,4) at parse — scale <= 4 and precision - scale <= 16 — and raw
  numeric text over a small max length is rejected before parsing; violations are IllegalArgumentException -> poison path
  (R61). Test both bounds and an exponent bomb. Cost if wrong: a legitimately over-precise amount is dead-lettered rather
  than rounded — which is correct for money.
- Ruling R64: stop() calls source.close() while holding the monitor; if the client's close waits for an in-flight handler blocked
  on that monitor -> deadlock. Carried into 17b (lifecycle owner): drain under the lock, close the source outside it.
- Ruling R65: reviewer minor promoted — asInt()/asLong() turn a non-numeric userId/type/productId into 0 SILENTLY (an item lands on
  user 0); Instant.parse throws DateTimeParseException. Carried into 17b's parser work (with R63): integral fields must be
  integral numbers (or integral numeric text), else IllegalArgumentException; parse errors wrap to IllegalArgumentException ->
  poison path. Tests for each.
- Ruling R66: IdempotencyPropertyTest adds spring.flyway.enabled=false (R46 pattern). jqwik-spring 0.12.0 must be verified
  compatible with jqwik 1.9.2 / Spring Boot 3.4 and must honour @DynamicPropertySource; if not, pick the compatible version or
  supply the properties another documented way. Cost if wrong: dependency churn only.
- Ruling R67 (pre-empting hollow #13): mandatory negative control — temporarily make ItemInserter ignore its gate (proceed to
  the view inserts when the locator insert affected 0 rows); P-1 must FAIL; record Jqwik's shrunk counterexample; revert.
  Snapshot also includes the deterministic "balanceAfter" and "cancelledBy" columns (excluding only itemId and ingestedAt,
  as the brief's own rationale states). Cost: one run, two columns.
- Ruling R68: fix round 1 also takes reviewer Minor 1 (DeadLetterStore.serialize swallows failures, writes {"unserializable":true},
  logs nothing — then the offset advances past an event whose data exists nowhere: a loss path) -> rethrow so recovery handles
  it; and Minor 3a (per-item null-offset mutation survives all tests) -> add the test. Also: perform the source close for
  recovery on the poll thread, not inside the client's message handler (self-close from the dispatch thread is unverifiable).
  Other minors deferred. Cost: small.
- Ruling R69: brief items 4/5 ("hook must not advance the offset") vs item 8 ("reuse the mapper-failure offset semantics") —
  controller's own brief inconsistency; item 8 is later and more specific and matches Task 17's reviewed semantics. The
  implementer's choice (write only when nothing is buffered) stands. Cost if wrong: none — that path was reviewed safe in Task 17.
- Ruling R70: money-bound check after stripTrailingZeros (1.50000 accepted) — accepted: the value is representable in numeric(20,4)
  without loss. Cost if wrong: none.
- Ruling R71: Environment/RabbitStreamSource moved to a package-private StreamBrokerConfig imported via DeferredImportSelector so
  @ConditionalOnMissingBean evaluates after user/test configuration (it is order-sensitive outside auto-config) — accepted in
  principle; review judges it. StreamLifecycle extracted (2 extra files) — accepted.
- Ruling R72: fix round 1 also takes reviewer Minor 3 (text fields coerce silently: currency {} stored as "", booleans/numbers
  stringified) -> require isTextual() for transactionId, currency, batchId, subBatchId, cancelsTransactionId (null/missing
  optional still -> null); and Minors 2 + 4 as one javadoc line each (start() must not run concurrently with poll(); subscribe's
  internal close is a no-op by construction). Cost: small.
- Ruling R73: RollupUpdater as briefed is ALL jOOQ plain-SQL API (dsl.execute(String,...), dsl.fetchOne(String,...)); the plan's own
  note says it passes because "the ArchUnit rule targets DSL.field/DSL.condition specifically" — i.e. it relied on the hollow
  rule R41 replaced. It now fails the build, correctly. jOOQ's typed DSL expresses every construct used: insertInto(...)
  .onConflict(...).doUpdate().set(F, F.plus(DSL.excluded(F))), DSL.greatest(...), selects on generated WQ_GROUP_LOCATOR. Spec
  2.2 rule 1: "Always use generated table and field constants". Ruled: typed DSL throughout; no @RawSqlJustified (not justified
  — the DSL can express it). Cost if wrong: none; same SQL.
- Ruling R74 (LARGEST RULING OF THE RUN — surface prominently to the user): the plan anchors a session's/play's "startedAt"
  (partition key AND primary-key component) to the FIRST-SEEN item, and its test session_end_moves_forward_but_start_does_not
  asserts that. Spec P-1 (binding): any permutation of an event sequence converges to identical projection state. With a
  first-seen anchor, session startedAt — and downstream the high-level page-index row (Task 20: its "occurredAt" IS the session
  startedAt) and the HIGH_LEVEL day/month counters (Task 21) — depend on arrival order. Out-of-order arrival is expected by the
  spec itself (early-cancel parking exists for it; dead-letter replay re-applies old items late). After R6 extends the
  snapshot to rollups (Task 22), P-1 would fail — or someone would exclude startedAt and hide it. The plan's own addendum
  defines startedAt as "the session's first item's timestamp" = earliest occurredAt.
  Ruled: startedAt = min(occurredAt) over the group. When an item EARLIER than the current anchor arrives, RE-ANCHOR inside the
  same transaction: delete the old row (returning its aggregates), insert at the new startedAt with aggregates combined with
  this item, update "wqGroupLocator". Applies to session and play independently. RollupUpdater.applyItem returns a
  RollupResult NOW (pre-empting Task 22's change): sessionIsNew, playIsNew, and previousSessionStart (non-null only when the
  session re-anchored) so Tasks 20/21 can move the page-index row and shift HIGH_LEVEL counters; those tasks' dispatches will
  carry the move. The briefed test is corrected to startedAt=T0, endedAt=T0+100, plus re-anchor tests (aggregates preserved,
  one row, locator updated, re-anchor across a month boundary moves partitions).
  Cost if wrong: extra delete+insert on the rare out-of-order item, and moving-row logic in Tasks 20/21. If the user decides
  first-seen semantics are acceptable, P-1 must explicitly exclude startedAt-derived state and the spec must say so.
- Ruling R75: fix scope — generate deliberate sourceId COLLISIONS ACROSS SOURCES (e.g. TRANSACTION "x1" and PAYMENT/MANUAL_ADJUSTMENT/
  CLAIMABLE_PRIZE "x1"), wire sources() so all non-LIMIT sources appear (respecting each source's sessioned/unsessioned shape);
  add "source" to the snapshot order-by; re-run the negative control with a sourceId-only gate mutation (must FAIL; record
  shrunk counterexample; revert). Cross-USER collisions under one source are NOT generated: a source's ids are unique within
  that source system, so same (source, sourceId) for two users is not a legitimate input and has no defined outcome.
  Also take reviewer Minor (routing): add an assertion that every row's view table matches its source().views() — P-1 is a
  convergence property and cannot see deterministic misrouting; a few lines catch it. And generate a non-null detailJson
  sometimes (JSONB round-trip). Other minors deferred. Cost: generator + 2 assertions.
- Ruling R76: (a) R73 applies — HighLevelUpdater's dsl.execute(String) upsert rewritten in typed DSL against WQ_WEB_HIGH_LEVEL /
  WQ_CC_HIGH_LEVEL (select the Table by ViewKind; the two tables share column shapes — a small helper or two explicit branches).
  (b) R74 carry: signature becomes applyItem(ProjectionItem item, RollupResult rollup) (not boolean sessionIsNew). When
  rollup.previousSessionStart() != null the session's page-index row MOVES in each view it feeds: delete (userId,
  previousSessionStart, kind 0, refId batchId) capturing "visibleExclCancelled", insert at the new startedAt (= the item's
  occurredAt, since a re-anchor means this item is now earliest) preserving visibleExclCancelled, productId, currency. Moves
  return 0 "inserted" (row count unchanged; Task 21/22 shift counters via previousSessionStart). Tests: move within a month,
  move across a month boundary (both partitions), visibility preserved by a move, still exactly one session row per view.
  Cost if wrong: one delete+insert on rare out-of-order items.
- Ruling R77: (a) R73 applies — CounterUpdater's dsl.execute(String) increments/decrements rewritten in typed DSL against
  WQ_COUNT_DAY / WQ_COUNT_MONTH (same column shapes; select the Table, no string formatting of table names).
  (b) R74 carry: add moveHighLevel(ProjectionItem item, Instant from, boolean visibleExclCancelled) — for each view the source
  feeds and each product key: decrement cntAll (and cntExclCancelled iff visible) at from's day/month, increment at the new
  start's day/month. A move within the same day is a no-op. Task 22 calls it when RollupResult.previousSessionStart != null.
  Tests: move across days, across months, invisible session moves only cntAll, same-day no-op.
  (c) VERIFY, don't assume: "wqCountDay" is partitioned on a DATE, and PartitionManager now writes bounds as UTC instant
  literals ('...Z', commit bc7089f) with a comment claiming they "cast unchanged to a date column too". The implementer must
  confirm partition creation for wqCountDay actually succeeds (PartitionManagerTest's count rises to 21 only if it does).
  Cost if wrong: none — verification only.
- Ruling R78 (generalises R74's carry): a session's page-index row (Task 20) and HIGH_LEVEL counters (Task 21) are keyed by the
  session's (startedAt, productId); with LEAST semantics either can change when an item arrives. RollupResult must report the
  session's PREVIOUS key whenever it changed — previousSessionStart AND previousSessionProductId (plus the current
  sessionStart/sessionProductId) — and Tasks 20/21 move along both axes (same delete+insert / decrement+increment). Tasks
  20/21/22 key session rows and HIGH_LEVEL counts on the SESSION's productId/startedAt (from RollupResult), never on the
  triggering item's. Lands in Task 19's fix round if the review requests changes, else as Task 20's first step.
  Cost if wrong: one more field and branch.
- Ruling R79: "wqGroupLocator" uses subBatchId = '' as the SESSION marker; an item with subBatchId "" (textual, so R72a's parser
  accepts it) would claim the session's locator key as a play — play and session rows cross-corrupt. Ruled into Task 19's fix
  round: ProjectionItem's compact constructor normalises blank batchId/subBatchId to null (isSessioned already treats blank
  batchId as unsessioned), making the marker unreachable from input; a test feeds subBatchId "" and "  " and asserts one session
  row, no play row, locator intact. Cost if wrong: none — blank ids carry no meaning.
- Ruling R80: on EVERY sessioned, non-cancellation item (new, join, move, product update) ensure the session row exists in every
  view the item's source feeds: insert-if-absent at the session's CURRENT key (start, productId) with visibleExclCancelled =
  true — correct because cancellations never reach here, so the triggering item is a live item in that view, i.e. the session
  has >=1 surviving item there. Materialisations COUNT as inserted (return value feeds HIGH_LEVEL counters in Task 21/22).
  Moves still move existing rows (preserving their visibility); a view missing its row is then materialised by the rule above.
  Rewrite the pinning test; add a mixed-source order test ([CC-only sessioned, TX] vs [TX, CC-only]) asserting identical rows.
  Also take Minors: behavioural MANDATORY test via the autowired bean (IllegalTransactionStateException outside a transaction);
  hoist the loop-invariant condition; assert (not silently fall back) when a sessioned item arrives with a null session start.
  Cost if wrong: an extra onConflictDoNothing insert per sessioned item per view.
- Ruling R81 (sent as an addendum into Task 20 fix round 1): HighLevelUpdater returns a per-view result (inserted views; moved
  views with previous start/product, product-only updates included) instead of an int — HIGH_LEVEL counters are per view, and
  with R80 materialisation a single call may insert in one view and move in another. R77b's moveHighLevel is refined
  accordingly: Task 21 exposes per-view count/move operations; Task 22 drives them from HighLevelResult.
  Cost if wrong: a richer return type.
- Ruling R82 (carry to Task 23): a session row hidden by cancellation (visibleExclCancelled=false) stays hidden when a later LIVE
  item joins (preserve-visibility rule). Visibility means "session has >=1 surviving item in that view", so a live join must
  REVIVE it (and re-increment HIGH_LEVEL excl-cancelled counters). Task 23 owns visibility transitions; its dispatch carries this.
- Ruling R83: brief Steps 3-4 are OBSOLETE (RollupResult already exists, richer, from R74/R78). Step 5's writer is rewritten to
  drive the per-view contract: for each item that passes the gate — rollup = rollupUpdater.applyItem(item);
  hl = highLevelUpdater.applyItem(item, rollup); counterUpdater.countItem(item); for each v in hl.inserted():
  countHighLevel(user, v, currentKey); for each v in hl.moved(): moveHighLevel(user, v, hl.movedFrom/ProductId -> currentKey,
  visibility-of-that-row). currentKey = session (start, productId) from RollupResult for sessioned items, else the item's
  (occurredAt, productId). GAP: HighLevelResult has no per-view visibility for moved rows, which moveHighLevel needs to decide
  whether to shift cntExclCancelled — add it (e.g. Map<ViewKind, Boolean> movedVisible) in this task. Cost: one field.
- Ruling R84 (executes preflight R6 now): extend ProjectionSnapshot to cover "wqGroupLocator", "wqSession", "wqPlay",
  "wqWebHighLevel", "wqCcHighLevel", "wqCountDay", "wqCountMonth" (all columns; none are volatile — verify), ordered by full PK.
  P-1 then exercises re-anchoring, materialisation and counter moves under permutation end-to-end — the payoff for R74.
  Plus an in-task oracle check in ProjectionWriterPhase3Test: HIGH_LEVEL cntAll per view (sentinel, by day) equals the actual
  page-index row count per view/day after a mixed, out-of-order batch (P-2 proper is Task 24).
  ProjectionWriterPhase3Test also needs spring.flyway.enabled=false (R46 pattern).
- Ruling R85 (I1, plan-mandated clamp): spec P-2 is binding — every stored counter equals the actual count. greatest(x-1,0) and a
  zero-row UPDATE silently convert a double-decrement / uncounted-key bug into permanent drift that P-2 then has to catch
  after the fact. By the gate + per-view mirror invariants every decremented key was counted first, so a miss is ALWAYS a bug.
  Ruled: drop the clamp; CHECK ("cntAll" >= 0 AND "cntExclCancelled" >= 0 AND "cntExclCancelled" <= "cntAll") on both counter
  tables (edit V7 in place — unreleased); decrements require exactly 1 affected row else IllegalStateException naming the key.
  The throw fails the projection transaction -> per-item fallback -> dead-letter (replayable, counted, alarmed) rather than
  silent drift. Tests: missing-row decrement throws; double uncount throws; CHECK rejects a negative (direct SQL in test).
  Cost if wrong: a latent counter bug surfaces as dead-letters instead of drift — which is the point.
- Ruling R86 (cancel resolution key): findOriginal looks up "wqItemLocator" by sourceId ALONE; the locator key is (source,
  sourceId) and P-1 generates cross-source collisions (R75) -> TooManyRowsException or the wrong item cancelled. markCancelled
  likewise filters only sourceId+occurredAt. Ruled: a cancel targets an item of its OWN source (a TRANSACTION cancel cancels a
  TRANSACTION; the brief's PAYMENT test cancels a PAYMENT) — resolve by (cancel.source, cancel.cancels); markCancelled filters by
  the full identity (userId, occurredAt, source, sourceId). "wqPendingCancel" stores the cancel's source; its uniqueness is
  ("source","cancelSourceId"); drain is keyed by (source, sourceId) of inserted items; drained cancels are rebuilt with their
  real source (not hard-coded TRANSACTION / "XXX"). Document the same-source assumption (O-13: confirm with the producer).
  Cost if wrong: if a cancel can target another source, resolution must widen — one query change.
- Ruling R87 (per-view visibility): visibleExclCancelled means "the session has >=1 surviving (non-cancelled) item IN THAT VIEW".
  The brief flips every view's row off when the session-wide txCountExclCancelled hits 0 — wrong when a CC-only live item
  survives. Ruled: after marking the original cancelled, for each view the original's source feeds, re-evaluate that view's
  session row: exists a non-cancelled item in that view's item table for (userId, batchId) within [session startedAt, endedAt]
  (partition-pruned)? If not and the row is visible -> flip false and uncount HIGH_LEVEL excl in that view. Unsessioned item:
  its own row flips false (as briefed). HIGH_LEVEL counter keys use the SESSION's (startedAt, productId) (R78), not the item's.
- Ruling R88 (revival, carries R82 down a level): a LIVE item joining a session whose row in a view it feeds is invisible must
  flip it back to true and re-count HIGH_LEVEL excl (+1 excl only; CHECK keeps excl <= all) — HighLevelUpdater reports
  "revived" views (extend HighLevelResult) and the writer counts them. Same shape for plays: a live item joining a play whose
  txCountExclCancelled is 0 (emptied by cancels) increments the session's playCountExclCancelled — RollupUpdater's upsert
  must detect "play was emptied" (not just "play is new"). Tests both levels.
- Ruling R89: R73 (typed DSL; `= any(?)` may be an IN list per R8) and R85 (no greatest(...) clamps in decrementPlay/Session —
  strict, a negative is a bug) apply throughout Task 23.
- Ruling R90: P-1 must cover cancels: Task 24 extends generators with cancels on both sides, but Task 23 adds a focused
  permutation test of its own: {t1, t2 (same session, different plays), cancel(t1), CC-only live item in the same session}
  in all orders that matter (cancel before/after its original; live CC item before/after the cancel) -> identical full
  projection state (reuse ProjectionSnapshot).
- Ruling R91 (P-2 counterDrift): checks only wqCountDay, only productId 0, and LEFT JOINs from actual (orphan stored counters and
  wrong per-product rows invisible). Ruled: FULL OUTER JOIN; both tiers (day and month); every product key (own product rows
  plus sentinel 0) for both units; HIGH_LEVEL actuals keyed by the row's (occurredAt day/month, productId) — session rows are
  keyed at the session key (R78). Cancellation item rows ARE item rows (Task 22's reading) and count in ITEM cntAll.
- Ruling R92 (P-4 aggregationDrift): ignores every ExclCancelled column (exactly what Task 23 changes) and derives session totals
  from plays (false drift for sessioned items without a play). Ruled: derive the expected session AND play rows from the ITEM
  rows (wqCcItem carries every source; exclude cancellation items, which never roll up): txCount, txCountExclCancelled,
  playCount = distinct non-null subBatchId, playCountExclCancelled = plays with >=1 non-cancelled item, totalPlays/Wins/net and
  their ExclCancelled forms (MoneyDirection mapping as code), startedAt = min, endedAt = max, productId = least; plus the
  group-locator agreeing with the rows. Keep "net = wins - plays" checks.
- Ruling R93 (P-8 highLevelDrift): checks only wqCcHighLevel; visibility from session-wide txCountExclCancelled contradicts R87;
  `batchId is null` includes cancellation items, which deliberately get NO page row (false 'missing row'). Ruled: both view
  tables; expected session row in view V iff the session has >=1 non-cancellation item whose source feeds V (at the session
  key); visible iff >=1 such item not cancelled; unsessioned expected rows exclude cancellation items, per view by routing.
- Ruling R94: ProjectionPropertyBase adds spring.flyway.enabled=false (R46) and reuses Task 22's clearProjection(); P-1
  (IdempotencyPropertyTest) also runs over itemSequencesWithCancels (spec P-1 names pending-cancel parking). Each oracle gets a
  negative control (corrupt one stored value via direct SQL -> its property fails).
- Ruling R95: ACCEPT delete-on-empty — V7's own comment: "Rows are sparse -- they exist only for periods where the user had
  activity". Constraint: delete only when cntAll reaches 0 (an excl-only decrement to 0 must keep the row while cntAll > 0).
  Re-review verifies. Cost if wrong: none — sparse rows are the documented model; readers coalesce missing rows to 0.
- Ruling R96: fold Task 23's cheap minors into Task 24's dispatch (before the generator-wide cancel properties land):
  (a) cancel userId must match the original's — include userId in the locator lookup and the drain match; a mismatch stays
  PARKED (visible via the oldest-age gauge), never throws (a throw on the drain path would dead-letter the innocent original);
  (b) CancellationTest LEAST order: add c1-then-c2 expecting c1;
  (c) lowerCancelledBy uses requireOne like every other update;
  (d) PendingCancelStore.park/drainFor @Transactional(MANDATORY);
  (e) comment at ProjectionWriter drain: a failing drained cancel dead-letters the triggering original (O-item).
- Ruling R97: Task 23 created a test-side ProjectionOracle (both count columns per view/unit/tier/product; per-view visibility).
  Task 24's ProjectionInvariants must not be a second, divergent oracle: merge into ONE class (keep the brief's name
  ProjectionInvariants, reusable by Phase 7 reconciliation jobs), implementing R91-R93 and absorbing ProjectionOracle's checks;
  update Task 23's callers. Cost: a rename/merge.
- Ruling R98: FIX NOW (cheap, real P-1 violation): session page rows carry the session's LEAST(currency) (stamp from the rollup on
  insert/move/revive/materialise; update when LEAST changes — report in HighLevelResult if it affects nothing else); P-8 expects
  min(currency) over the session's non-cancellation items and the item's own currency for unsessioned rows; the generator
  occasionally gives an adjustment/prize a second currency. The OPEN QUESTION stays with the user (should mixed-currency
  sessions be rejected or split?) — this makes behaviour deterministic either way. Also: Minor 1 (decide full cancellation once
  per distinct batchId so partially-cancelled multi-play sessions are common), Minor 2 (the missing corruption controls: P-2
  excl-only; P-4 orphan play / missing session / playCount + playCountExclCancelled; P-8 missing session row in one view),
  Minor 3 (class-doc wording on independence). Cost: small.
- Ruling R99 (final fix scope — ONE dispatch, opus): FIX C1, I1, I2, I3, I5 (offset.lag + deadletter.depth implemented; batch.size/
  duration and job.* added if cheap, else listed in the plan's Deferred table), I6 (break the cycle + add the 8.5 layering rule),
  and these minors: widen web/operator rule (WqCc prefix + Tables.WQ_CC_* access), cancellation invariant in ProjectionInvariants,
  cron .inTimeZone(UTC), clearProjection includes wqDeadLetter, per-item offset + true attempts on dead-letter rows, trivial
  triage items (PartitionManager justification names .and(String,...); unused SourceKind import).
- Ruling R100 (I1 policy): on-demand partition provisioning in its OWN transaction (REQUIRES_NEW, cached per month) invoked at the
  start of ProjectionWriter.apply for every month the batch's items touch (occurredAt months cover re-anchor targets and
  wqCountDay periods), BEFORE any projection write — never DDL inside the projection transaction (ACCESS EXCLUSIVE on the parent).
  Sanity window: months after now + 12 are rejected as poison (IllegalArgumentException -> dead-letter: a garbage timestamp, not a
  partition to create); no past floor (first deploy replays from offset 0 — O-9 backfill). Cost if wrong: many small partitions
  on a deep replay — acceptable, monthly granularity.
- Ruling R101 (I4): DEFER the mechanism, enforce by documentation now — "exactly one replica may set WQS_STREAM_ENABLED=true" in
  application.yaml, StreamConfig javadoc and spec 6.1; single-active-consumer / advisory lock listed in the Deferred table.
  Plus a startup WARN log when wqs.stream.enabled=false (triage item).
- Ruling R102 (spec doc): the plan requires its spec changes before merging. The fix dispatch also edits the spec for the reviewer's
  12 items, marking two as OPEN DECISIONS for the user, not resolved: (a) cancel resolution scope (R86 same-source vs spec 6.3's
  "different source"), (b) mixed-currency sessions (current: LEAST, deterministic). And it updates the plan's Deferred table
  (single-active-consumer, ProjectionInvariants to main for Phase 7, remaining 11.2 metrics if not done, broker->DB e2e test,
  wqCountMonth yearly partitioning decision, consumer start failure policy, parked-cancel expiry/alert).
- Ruling R103 (amends R100's "no past floor"): garbage old timestamps would create partitions per distinct ancient month without
  bound. Ruled: configurable wqs.partition.provision-floor (default 2010-01); months before the floor are poison (dead-lettered)
  before any DDL — bounds total partitions to (now+12 - floor) x tables (~1400). No per-batch cap (would dead-letter legitimate
  deep replays). Partition names formatted with proleptic 'uuuu' instead of 'yyyy'. Sent as an addendum into the fix wave.
  Cost if wrong: legitimate events older than 2010 are dead-lettered — the floor is configurable.
- Ruling R104: the residual is load-bearing (spec 8.5) but is the fix wave's OWN regression of an item it was briefed to do —
  finishing that item, not a second wave. Resume the same implementer: restore the four rules verbatim from e1518f6, remove the
  stray import only if still unused, re-run the negative control (reintroduce a projection -> ingest import -> both rules fail;
  revert); plus test pool size (spring.datasource.hikari.maximum-pool-size 4 in test config/application.yaml, revert
  max_connections to default if the suite passes) and the comment fix. Controller verifies by running ArchitectureTest and the
  full build, and checks the diff touches only test files. Cost if wrong: none — restores required protection.
