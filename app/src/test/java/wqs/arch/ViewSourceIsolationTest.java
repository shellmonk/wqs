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
 * mentions an operator table or type ({@code wqPrjCc...}, any case, so {@code WqPrjCc...} type names
 * too), and no file under the cc packages mentions a web one ({@code wqPrjWeb...}).
 *
 * <p>Replaces an ArchUnit rule over generated {@code WqCc*} table types, which SQL text strings
 * bypass. The scan sees only names written literally in those directories: a table name built
 * indirectly (concatenated, or taken from a constant elsewhere) is not covered, and
 * {@code WebProjectionSecurityTest} is the end-to-end guard for the same control.
 */
class ViewSourceIsolationTest {

    private static final List<String> WEB = List.of("wqs/read/web", "wqs/api/web");
    private static final List<String> CC = List.of("wqs/read/cc", "wqs/api/cc");

    @Test
    void web_sources_never_name_an_operator_table() {
        assertThat(mentions(mainSources(WEB), "wqprjcc")).isEmpty();
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
                "class LeakyWebAssembly { WqPrjCcHighLevelRow row; }");
        Files.writeString(dir.resolve("CleanWebQuery.java"),
                "class CleanWebQuery { String sql = \"select * from \\\"wqPrjWebTransaction\\\"\"; }");

        assertThat(mentions(List.of(dir), "wqprjcc"))
                .hasSize(2)
                .anyMatch(m -> m.contains("LeakyWebQuery.java:1") && m.contains("\"wqPrjCcTransaction"))
                .anyMatch(m -> m.contains("LeakyWebAssembly.java:1") && m.contains("WqPrjCcHighLevelRow"))
                .noneMatch(m -> m.contains("CleanWebQuery"));
        assertThat(mentions(List.of(dir), "wqprjweb")).singleElement().asString().contains("CleanWebQuery");
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

    /** {@code file:line: text} for every line containing {@code needle}, case-insensitively. */
    private static List<String> mentions(List<Path> dirs, String needle) {
        return dirs.stream().flatMap(d -> javaFiles(d).stream()).flatMap(file -> {
            List<String> lines = read(file);
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(i -> lines.get(i).toLowerCase(Locale.ROOT).contains(needle))
                    .mapToObj(i -> file + ":" + (i + 1) + ": " + lines.get(i).strip());
        }).toList();
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
