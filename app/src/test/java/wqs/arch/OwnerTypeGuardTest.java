package wqs.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Owner identity is ("ownerType", "userId") (plan D1): every owner type is stored, so a statement
 * that filters by "userId" alone would merge owner "2-1717" into player 1717. SQL is Java text, so
 * this is checked on the source: every text block under the projection and read packages that
 * compares "userId" also compares "ownerType". (That every read query implementation holds its
 * endpoint's read policy is ReadPolicyGuardTest's.)
 */
class OwnerTypeGuardTest {

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern USER_FILTER = Pattern.compile("\"userId\"\\s*(=|in\\b)");
    private static final Pattern OWNER_FILTER = Pattern.compile("\"ownerType\"\\s*=");

    @Test
    void every_statement_filtering_by_user_also_filters_by_owner_type() {
        assertThat(unownedStatements(sources("wqs/projection", "wqs/read"))).isEmpty();
    }

    /** The guard can fail. */
    @Test
    void the_guard_reports_a_user_filter_without_the_owner_type() throws IOException {
        Path dir = Files.createTempDirectory("owner-guard");
        Path bad = dir.resolve("Bad.java");
        Files.writeString(bad, "class Bad { String sql = \"\"\"\n select 1 from \"wqPrjSession\" where \"userId\" = ?\n \"\"\"; }");
        Path good = dir.resolve("Good.java");
        Files.writeString(good, "class Good { String sql = \"\"\"\n select 1 from \"wqPrjSession\"\n"
                + " where \"ownerType\" = ? and \"userId\" = ?\n \"\"\"; }");
        assertThat(unownedStatements(List.of(bad, good))).singleElement().asString().startsWith("Bad.java");
    }

    private static List<String> unownedStatements(List<Path> files) {
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            String source = read(file);
            Matcher m = TEXT_BLOCK.matcher(source);
            while (m.find()) {
                String sql = m.group(1);
                int users = count(USER_FILTER, sql);
                if (users > 0 && count(OWNER_FILTER, sql) < users) {
                    out.add(file.getFileName() + ": " + sql.strip().replaceAll("\\s+", " "));
                }
            }
        }
        return out;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static List<Path> sources(String... packages) {
        Path root = Stream.of(Path.of("src/main/java"), Path.of("app/src/main/java"))
                .filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("main sources not found from " + Path.of("").toAbsolutePath()));
        List<Path> out = new ArrayList<>();
        for (String pkg : packages) {
            try (Stream<Path> walk = Files.walk(root.resolve(pkg))) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
