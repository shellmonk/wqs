package wqs.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec 9 control 3, security by schema: /web cannot reach operator data, and /cc reads only its
 * own tables. SQL is Java text, so this is checked on the source: no file under the web packages
 * mentions an operator view or type ({@code wqPrjCc...}, any case, so {@code WqPrjCc...} type names
 * too) or the shared base table {@code "wqPrjTransaction"} (it holds every source; /web reads only
 * the {@code "wqPrjWebTransaction"} view over it), every web file that reads {@code "wqPrjTimeline"}
 * restricts it to {@code "onWeb"}, and no file under the cc packages mentions a web one
 * ({@code wqPrjWeb...}).
 *
 * <p>Replaces an ArchUnit rule over generated {@code WqCc*} table types, which SQL text strings
 * bypass. The scan sees only names written literally in those directories: a table name built
 * indirectly (concatenated, or taken from a constant elsewhere) is not covered, and
 * {@code WebProjectionSecurityTest} is the end-to-end guard for the same control.
 */
class ViewSourceIsolationTest {

    private static final List<String> WEB = List.of("wqs/read/web", "wqs/api/web");
    private static final List<String> CC = List.of("wqs/read/cc", "wqs/api/cc");
    /** The base table, quoted: its views and its locator ("wqPrjTransactionLocator") do not match. */
    private static final String BASE_TABLE = "\"wqprjtransaction\"";
    /** The /web timeline predicate as the SQL writes it, qualified. */
    private static final String ON_WEB = "\"wqprjtimeline\".\"onweb\"";

    @Test
    void web_sources_never_name_an_operator_table() {
        assertThat(mentions(mainSources(WEB), "wqprjcc")).isEmpty();
    }

    @Test
    void web_sources_never_name_the_base_transaction_table() {
        assertThat(mentions(mainSources(WEB), BASE_TABLE)).isEmpty();
    }

    @Test
    void web_sources_read_the_timeline_only_on_web() {
        assertThat(timelineWithoutOnWeb(mainSources(WEB))).isEmpty();
    }

    /**
     * Final-review M2: the top-level {@code wqs/read} files (non-recursive) are shared code that
     * also serves /web -- the walk, counters, assembly. Today they name only "wqPrjCount",
     * "wqPrjSession" and "wqPrjPlay"; they must never name the operator view ({@code wqPrjCc...})
     * or the base transaction table, nor read the timeline without "onWeb".
     */
    @Test
    void shared_read_sources_never_name_an_operator_table_or_the_base_table() {
        List<Path> shared = sharedReadSources();
        assertThat(mentionsIn(shared, "wqprjcc")).isEmpty();
        assertThat(mentionsIn(shared, BASE_TABLE)).isEmpty();
        assertThat(timelineWithoutOnWebIn(shared)).isEmpty();
    }

    /** Proves the shared scan can fail, and that it does not descend into the endpoint packages. */
    @Test
    void the_shared_scan_reports_leaks_and_is_not_recursive(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("SharedWalk.java"),
                "class SharedWalk { String sql = \"select 1 from \\\"WQPRJCCTRANSACTION\\\"\"; }");
        Files.writeString(dir.resolve("SharedBase.java"),
                "class SharedBase { String sql = \"select 1 from \\\"wqPrjTransaction\\\"\"; }");
        Files.writeString(dir.resolve("SharedClean.java"),
                "class SharedClean { String sql = \"select 1 from \\\"wqPrjSession\\\"\"; }");
        Path cc = Files.createDirectory(dir.resolve("cc"));
        Files.writeString(cc.resolve("CcQuery.java"),
                "class CcQuery { String sql = \"select 1 from \\\"wqPrjCcTransaction\\\"\"; }");

        List<Path> shared = topLevelJavaFiles(dir);
        assertThat(shared).extracting(f -> f.getFileName().toString())
                .containsExactly("SharedBase.java", "SharedClean.java", "SharedWalk.java");
        assertThat(mentionsIn(shared, "wqprjcc")).singleElement().asString().contains("SharedWalk.java:1");
        assertThat(mentionsIn(shared, BASE_TABLE)).singleElement().asString().contains("SharedBase.java:1");
    }

    @Test
    void cc_sources_never_name_a_web_table() {
        assertThat(mentions(mainSources(CC), "wqprjweb")).isEmpty();
    }

    /** Proves the scan can fail: a SQL string and a type name, each in a different case. */
    @Test
    void the_scan_reports_every_mention_in_any_case(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("LeakyWebQuery.java"),
                "class LeakyWebQuery { String sql = \"select * from \\\"wqPrjCcTransaction\\\"\"; }");
        Files.writeString(dir.resolve("LeakyWebAssembly.java"),
                "class LeakyWebAssembly { WqPrjCcTransactionRow row; }");
        Files.writeString(dir.resolve("CleanWebQuery.java"),
                "class CleanWebQuery { String sql = \"select * from \\\"wqPrjWebTransaction\\\"\"; }");

        assertThat(mentions(List.of(dir), "wqprjcc"))
                .hasSize(2)
                .anyMatch(m -> m.contains("LeakyWebQuery.java:1") && m.contains("\"wqPrjCcTransaction"))
                .anyMatch(m -> m.contains("LeakyWebAssembly.java:1") && m.contains("WqPrjCcTransactionRow"))
                .noneMatch(m -> m.contains("CleanWebQuery"));
        assertThat(mentions(List.of(dir), "wqprjweb")).singleElement().asString().contains("CleanWebQuery");
    }

    /** Proves the base-table and timeline scans can fail, and pass the view and an "onWeb" read. */
    @Test
    void the_base_table_and_timeline_scans_report_leaks(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("BaseTableQuery.java"),
                "class BaseTableQuery { String sql = \"select * from \\\"wqPrjTransaction\\\"\"; }");
        Files.writeString(dir.resolve("AllTimelineQuery.java"),
                "class AllTimelineQuery { String sql = \"select * from \\\"wqPrjTimeline\\\"\"; }");
        Files.writeString(dir.resolve("WebTimelineQuery.java"),
                "class WebTimelineQuery { String sql = \"select * from \\\"wqPrjTimeline\\\" where \\\"wqPrjTimeline\\\".\\\"onWeb\\\" "
                        + "and exists (select 1 from \\\"wqPrjWebTransaction\\\")\"; }");

        assertThat(mentions(List.of(dir), BASE_TABLE)).singleElement().asString().contains("BaseTableQuery.java:1");
        assertThat(timelineWithoutOnWeb(List.of(dir))).singleElement().asString().contains("AllTimelineQuery.java");
    }

    /**
     * Files that name "wqPrjTimeline" but never the qualified predicate "wqPrjTimeline"."onWeb" (a
     * comment mentioning "onWeb" does not count).
     */
    private static List<String> timelineWithoutOnWeb(List<Path> dirs) {
        return timelineWithoutOnWebIn(dirs.stream().flatMap(d -> javaFiles(d).stream()).toList());
    }

    private static List<String> timelineWithoutOnWebIn(List<Path> files) {
        return files.stream()
                .filter(file -> {
                    String text = normalise(String.join("\n", read(file)));
                    return text.contains("\"wqprjtimeline\"") && !text.contains(ON_WEB);
                })
                .map(Path::toString)
                .toList();
    }

    /** The top-level wqs/read Java files, required to exist (no hollow pass). */
    private static List<Path> sharedReadSources() {
        Path root = Stream.of(Path.of("src/main/java"), Path.of("app/src/main/java"))
                .filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("main sources not found from " + Path.of("").toAbsolutePath()));
        List<Path> files = topLevelJavaFiles(root.resolve("wqs/read"));
        assertThat(files).as("shared read sources").isNotEmpty();
        return files;
    }

    /** The Java files directly in {@code dir}, not in its subpackages. */
    private static List<Path> topLevelJavaFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The package directories, each required to exist and hold Java sources (no hollow pass). */
    private static List<Path> mainSources(List<String> packages) {
        Path root = Stream.of(Path.of("src/main/java"), Path.of("app/src/main/java"))
                .filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("main sources not found from " + Path.of("").toAbsolutePath()));
        List<Path> dirs = packages.stream().map(root::resolve).toList();
        for (Path d : dirs) {
            assertThat(javaFiles(d)).as("Java sources under " + d).isNotEmpty();
        }
        return dirs;
    }

    /** {@code file:line: text} for every line containing {@code needle}, case-insensitively, escapes read as quotes. */
    private static List<String> mentions(List<Path> dirs, String needle) {
        return mentionsIn(dirs.stream().flatMap(d -> javaFiles(d).stream()).toList(), needle);
    }

    private static List<String> mentionsIn(List<Path> files, String needle) {
        return files.stream().flatMap(file -> {
            List<String> lines = read(file);
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(i -> normalise(lines.get(i)).contains(needle))
                    .mapToObj(i -> file + ":" + (i + 1) + ": " + lines.get(i).strip());
        }).toList();
    }

    /** Lower case, with Java's escaped quote \\" read as the quote it is in the SQL. */
    private static String normalise(String source) {
        return source.toLowerCase(Locale.ROOT).replace("\\\"", "\"");
    }

    private static List<Path> javaFiles(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
