package wqs.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import wqs.dto.ProjectionRequest;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.entity.SessionEntity;
import wqs.repository.PlayRepository;
import wqs.repository.SessionRepository;
import wqs.repository.TransactionRepository;
import wqs.service.PartitionService;
import wqs.service.ProjectionService;

/**
 * Read-side benchmark: does a plain COUNT(*) total stay fast enough without counter tables?
 * Run with {@code ./gradlew benchmark}; never part of the normal build.
 *
 * <p>Seeds about 3.1M rows over 24 monthly partitions with set-based SQL (the session and play
 * totals are built by GROUP BY with the same rules as ingest): 40,000 ordinary players with 50 rows
 * each, and three busy players with 10k, 100k and 1M rows. Rows are 70% in sessions (10 rows, 5
 * plays of a bet and a prize, half the prizes zero-amount) and 30% outside a session (a third of
 * those payments). Each case runs once cold and 5 times warm through {@link ProjectionService}, so
 * the time includes the JPA and native queries and the total. Writes build/benchmark/read-benchmark.md.
 */
@Tag("benchmark")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReadBenchmark {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withSharedMemorySize(2L * 1024 * 1024 * 1024)
            .withCommand("postgres", "-c", "shared_buffers=1GB", "-c", "work_mem=32MB",
                    "-c", "effective_cache_size=3GB", "-c", "max_wal_size=8GB", "-c", "synchronous_commit=off");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wqs.stream.enabled", () -> "false");
    }

    static final Instant START = Instant.parse("2024-11-01T00:00:00Z");
    static final Instant END = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant LAST_MONTH = Instant.parse("2026-10-01T00:00:00Z");
    static final int WARM_RUNS = 5;

    record Owner(long userId, int rows) {}

    static final List<Owner> BUSY = List.of(new Owner(900_001, 10_000), new Owner(900_002, 100_000), new Owner(900_003, 1_000_000));

    @Autowired JdbcClient jdbc;
    @Autowired PartitionService partitions;
    @Autowired ProjectionService projections;
    @Autowired SessionRepository sessions;
    @Autowired TransactionRepository transactions;
    @Autowired PlayRepository plays;
    @Autowired PlatformTransactionManager transactionManager;

    private final StringBuilder report = new StringBuilder();

    @Test
    void measure() throws IOException {
        long seedStart = System.nanoTime();
        seed();
        long seedMillis = Duration.ofNanos(System.nanoTime() - seedStart).toMillis();

        report.append("# Read benchmark\n\n")
                .append("Postgres 16 in a container (shared_buffers 1GB), ").append(count("\"wqPrjTransaction\""))
                .append(" rows over 24 monthly partitions, ").append(count("\"wqPrjSession\"")).append(" sessions, ")
                .append(count("\"wqPrjPlay\"")).append(" plays. Seeded in ").append(seedMillis / 1000).append(" s.\n\n")
                .append("Times are the whole service call (page + `COUNT(*)` total), in ms: one cold run, then the median of ")
                .append(WARM_RUNS).append(" warm runs. `/cc` policy (every source).\n\n")
                .append("| owner rows | case | total rows | cold ms | warm ms |\n|---:|---|---:|---:|---:|\n");

        for (Owner owner : BUSY) {
            run(owner, "L0 · last month · page 1 of 50", request(LAST_MONTH, END, 0, 1, 50, List.of()));
            run(owner, "L0 · 24 months · page 1 of 50", request(START, END, 0, 1, 50, List.of()));
            run(owner, "L0 · 24 months · page 100 of 50", request(START, END, 0, 100, 50, List.of()));
            run(owner, "L0 · 24 months · type PRIZE", request(START, END, 0, 1, 50, List.of("PRIZE")));
            run(owner, "L1 · last month · page 1 of 50", request(LAST_MONTH, END, 1, 1, 50, List.of()));
            run(owner, "L1 · 24 months · page 1 of 50", request(START, END, 1, 1, 50, List.of()));
            run(owner, "L1 · 24 months · type PRIZE", request(START, END, 1, 1, 50, List.of("PRIZE")));
            run(owner, "L2 · 24 months · page 1 of 20", request(START, END, 2, 1, 20, List.of()));
        }

        Owner busiest = BUSY.getLast();
        breakdown(busiest);
        report.append("\n## Query plans for the ").append(busiest.rows()).append("-row owner, 24 months\n");
        explain("Level 0 total", """
                select count(*) from "wqPrjTransaction"
                where "userId" = %d and "ownerType" = 1 and "occurredAt" >= '%s' and "occurredAt" < '%s'
                  and "source" in (1, 2, 3, 4, 5) and not "zeroAmount"
                """.formatted(busiest.userId(), START, END));
        explain("Level 0 page 1", """
                select * from "wqPrjTransaction"
                where "userId" = %d and "ownerType" = 1 and "occurredAt" >= '%s' and "occurredAt" < '%s'
                  and "source" in (1, 2, 3, 4, 5) and not "zeroAmount"
                order by "occurredAt" desc, "transactionId" desc limit 50
                """.formatted(busiest.userId(), START, END));
        explain("Levels 1-2 total", "select count(*) from (" + entries(busiest, List.of()) + ") e");
        explain("Levels 1-2 page 1", "select e.\"kind\", e.\"id\" from (" + entries(busiest, List.of())
                + ") e order by e.\"at\" desc, e.\"kind\" desc, e.\"id\" desc limit 50");
        explain("Levels 1-2 total, type PRIZE", "select count(*) from (" + entries(busiest, List.of("PRIZE")) + ") e");

        Path out = Path.of("build/benchmark/read-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    // --- seeding ---

    private void seed() {
        for (YearMonth m = YearMonth.from(START.atOffset(ZoneOffset.UTC)); m.isBefore(YearMonth.from(END.atOffset(ZoneOffset.UTC))); m = m.plusMonths(1)) {
            partitions.ensure("wqPrjTransaction", m.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        jdbc.sql("create temporary table if not exists \"benchOwner\" (\"userId\" bigint, \"rows\" int)").update();
        jdbc.sql("""
                insert into "benchOwner" select 1000000 + g, 50 from generate_series(1, 40000) g
                """).update();
        for (Owner o : BUSY) {
            jdbc.sql("insert into \"benchOwner\" values (:u, :n)").param("u", o.userId()).param("n", o.rows()).update();
        }
        // i counts an owner's rows oldest first; 10 consecutive rows form a group. 7 groups in 10 are
        // sessions (5 plays of a bet and a prize), the rest are rows without a session.
        jdbc.sql("""
                insert into "wqPrjTransaction" ("ownerType", "userId", "occurredAt", "transactionId", "source", "sourceId",
                    "type", "amount", "currency", "productId", "productType", "batchId", "subBatchId", "detail")
                select 1, o."userId",
                       timestamptz '2024-11-01 00:00:00+00' + interval '730 days' * ((i + 0.5) / o."rows"),
                       nextval('"wqTransactionIdSeq"'),
                       case when s then 1 when i % 3 = 0 then 4 else 1 end,
                       o."userId" || '-' || i,
                       case when s then (case when i % 2 = 0 then 'TT_BUY' else 'PRIZE' end)
                            when i % 3 = 0 then 'PT_BUY' else 'TT_TRANSFER_OUT' end,
                       case when s then (case when i % 2 = 0 then -2 when (i / 2) % 2 = 0 then 0 else 5 end) else -10 end,
                       'EUR', 'GAME', case when (i / 10) % 2 = 0 then 'instants' else 'lotto' end,
                       case when s then 'S-' || o."userId" || '-' || (i / 10) end,
                       case when s then 'P-' || o."userId" || '-' || (i / 2) end,
                       '{}'
                from "benchOwner" o
                cross join lateral generate_series(0, o."rows" - 1) i
                cross join lateral (select (i / 10) % 10 < 7 as s) k
                """).update();
        jdbc.sql("""
                insert into "wqPrjSession" ("ownerType", "userId", "batchId", "startedAt", "endedAt", "productId", "productType",
                    "currency", "txCount", "playCount", "totalPlays", "totalWins", "net",
                    "txCountExclCancelled", "playCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
                select "ownerType", "userId", "batchId", "startedAt", "endedAt", "productId", "productType",
                       'EUR', n, plays, p, w, w - p, n, plays, p, w, w - p
                from (select "ownerType", "userId", "batchId", min("occurredAt") as "startedAt", max("occurredAt") as "endedAt",
                             min("productId") as "productId", min("productType") as "productType", count(*) as n,
                             count(distinct "subBatchId") as plays,
                             sum(case when "type" = 'TT_BUY' then abs("amount") else 0 end) as p,
                             sum(case when "type" = 'PRIZE' then abs("amount") else 0 end) as w
                      from "wqPrjTransaction" where "batchId" is not null and not "zeroAmount"
                      group by "ownerType", "userId", "batchId") g
                """).update();
        jdbc.sql("""
                insert into "wqPrjPlay" ("sessionId", "ownerType", "userId", "batchId", "subBatchId", "startedAt", "endedAt",
                    "productId", "productType", "currency", "txCount", "totalPlays", "totalWins", "net",
                    "txCountExclCancelled", "totalPlaysExclCancelled", "totalWinsExclCancelled", "netExclCancelled")
                select s."sessionId", t."ownerType", t."userId", t."batchId", t."subBatchId", min(t."occurredAt"), max(t."occurredAt"),
                       min(t."productId"), min(t."productType"), 'EUR', count(*),
                       sum(case when t."type" = 'TT_BUY' then abs(t."amount") else 0 end),
                       sum(case when t."type" = 'PRIZE' then abs(t."amount") else 0 end),
                       sum(case when t."type" = 'PRIZE' then abs(t."amount") else 0 end) - sum(case when t."type" = 'TT_BUY' then abs(t."amount") else 0 end),
                       count(*),
                       sum(case when t."type" = 'TT_BUY' then abs(t."amount") else 0 end),
                       sum(case when t."type" = 'PRIZE' then abs(t."amount") else 0 end),
                       sum(case when t."type" = 'PRIZE' then abs(t."amount") else 0 end) - sum(case when t."type" = 'TT_BUY' then abs(t."amount") else 0 end)
                from "wqPrjTransaction" t
                join "wqPrjSession" s on s."ownerType" = t."ownerType" and s."userId" = t."userId" and s."batchId" = t."batchId"
                where t."batchId" is not null and not t."zeroAmount"
                group by s."sessionId", t."ownerType", t."userId", t."batchId", t."subBatchId"
                """).update();
        jdbc.sql("analyze").update();
    }

    // --- measuring ---

    private void run(Owner owner, String name, ProjectionRequest request) {
        Supplier<ProjectionResponse<?>> call = () -> projections.page(ReadPolicy.CC, owner.userId(), request);
        long cold = time(call);
        long[] warm = new long[WARM_RUNS];
        ProjectionResponse<?> last = null;
        for (int i = 0; i < WARM_RUNS; i++) {
            long t0 = System.nanoTime();
            last = call.get();
            warm[i] = System.nanoTime() - t0;
        }
        Arrays.sort(warm);
        report.append("| ").append(owner.rows()).append(" | ").append(name).append(" | ").append(last.totalRowCount())
                .append(" | ").append(Duration.ofNanos(cold).toMillis()).append(" | ")
                .append(Duration.ofNanos(warm[WARM_RUNS / 2]).toMillis()).append(" |\n");
    }

    private static long time(Supplier<?> call) {
        long t0 = System.nanoTime();
        call.get();
        return System.nanoTime() - t0;
    }

    private static ProjectionRequest request(Instant from, Instant to, int level, int page, int size, List<String> types) {
        return ProjectionRequest.of(from, to, level, false, List.of(), types, page, size);
    }

    /** The levels 1-2 entries query with this owner's values written in, for EXPLAIN. */
    private static String entries(Owner owner, List<String> types) {
        String typeArray = "'{" + String.join(",", types) + "}'";
        return SessionRepository.ENTRIES
                .replace(":userId", Long.toString(owner.userId()))
                .replace(":ownerType", "1")
                .replace(":from", "'" + START + "'")
                .replace(":to", "'" + END + "'")
                .replace(":sources", "'{1,2,3,4,5}'")
                .replace(":excludeCancelled", "false")
                .replace(":anyProductType", "true")
                .replace(":productTypes", "'{}'")
                .replace(":anyType", Boolean.toString(types.isEmpty()))
                .replace(":transactionTypes", typeArray);
    }

    /** Where the time of one level-1 page (24 months, page 1 of 50) goes, step by step, warm. */
    private void breakdown(Owner owner) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        String[] none = new String[0];
        Integer[] sources = ReadPolicy.CC.sourceCodes();
        report.append("\n## Level-1 page broken down (").append(owner.rows()).append("-row owner, 24 months, page 1 of 50), warm ms\n\n")
                .append("| step | ms |\n|---|---:|\n");
        for (int warmup = 0; warmup < 3; warmup++) {
            tx.executeWithoutResult(status -> sessions.countPageEntries(owner.userId(), (short) 1, START, END, sources, false, true, none, true, none));
        }
        tx.executeWithoutResult(status -> {
            long t0 = System.nanoTime();
            sessions.countPageEntries(owner.userId(), (short) 1, START, END, sources, false, true, none, true, none);
            long t1 = System.nanoTime();
            var entries = sessions.findPageEntries(owner.userId(), (short) 1, START, END, sources, false, true, none, true, none, 50, 0);
            long t2 = System.nanoTime();
            List<Long> ids = entries.stream().filter(e -> e.getKind() == 0).map(SessionRepository.PageEntry::getId).toList();
            List<SessionEntity> found = sessions.findAllById(ids);
            long t3 = System.nanoTime();
            Instant from = found.stream().map(SessionEntity::getStartedAt).min(Instant::compareTo).orElse(START);
            Instant to = found.stream().map(SessionEntity::getEndedAt).max(Instant::compareTo).orElse(END);
            int rows = transactions.findSessionRows(owner.userId(), (short) 1, found.stream().map(SessionEntity::getBatchId).toList(),
                    from, to, List.of((short) 1, (short) 2, (short) 3, (short) 4, (short) 5), true).size();
            long t4 = System.nanoTime();
            var txEntries = entries.stream().filter(e -> e.getKind() == 1).toList();
            List<Long> txIds = txEntries.stream().map(SessionRepository.PageEntry::getId).toList();
            transactions.findByIds(owner.userId(), txIds,
                    txEntries.stream().map(SessionRepository.PageEntry::getAt).min(Instant::compareTo).orElse(START),
                    txEntries.stream().map(SessionRepository.PageEntry::getAt).max(Instant::compareTo).orElse(END));
            long t5 = System.nanoTime();
            plays.findBySessionIdInOrderByStartedAtDescPlayIdDesc(ids);
            long t6 = System.nanoTime();
            report.append("| count entries (native) | ").append(ms(t1 - t0)).append(" |\n")
                    .append("| page of entries (native) | ").append(ms(t2 - t1)).append(" |\n")
                    .append("| load ").append(found.size()).append(" sessions | ").append(ms(t3 - t2)).append(" |\n")
                    .append("| load their ").append(rows).append(" rows | ").append(ms(t4 - t3)).append(" |\n")
                    .append("| load ").append(txIds.size()).append(" rows without a session | ").append(ms(t5 - t4)).append(" |\n")
                    .append("| load plays (level 2 only) | ").append(ms(t6 - t5)).append(" |\n");
        });
    }

    private static long ms(long nanos) {
        return Duration.ofNanos(nanos).toMillis();
    }

    private void explain(String title, String sql) {
        List<String> plan = new ArrayList<>(jdbc.sql("explain (analyze, buffers) " + sql).query(String.class).list());
        report.append("\n### ").append(title).append("\n\n```\n").append(String.join("\n", plan)).append("\n```\n");
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
