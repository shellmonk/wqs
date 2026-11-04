package wqs.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wqs.read.HighLevelQuery;
import wqs.read.HighLevelTransactionQuery;
import wqs.read.Level0Query;
import wqs.read.ReadPolicy;

/**
 * Plan D1, the guard that replaces the per-endpoint views (spec 9 control 3: /web cannot reach
 * operator data). Nothing stored knows an endpoint; each endpoint's {@link ReadPolicy} is bound into
 * every read instead. SQL is Java text, so this is checked on the source:
 * <ul>
 *   <li>every text block under {@code wqs/read} that reads "wqPrjTransaction" or "wqOwnerMonth" binds
 *       the policy's sources ({@code "source" = any (cast(:sources as smallint[]))}, or joined through
 *       {@code unnest(cast(:sources as smallint[]))}) and zero-amount
 *       rule ({@code cast(:includeZeroAmounts as boolean)}) once per read, and the owner type;</li>
 *   <li>every text block that pages sessions by time ("startedAt" bounded by {@code :lo}) binds the
 *       policy's sources on their "firstSource";</li>
 *   <li>no main source names an endpoint relation or column (the dropped views, the page index,
 *       "onWeb", "viewKind");</li>
 *   <li>every read query implementation holds its endpoint's {@link ReadPolicy} (ArchUnit).</li>
 * </ul>
 * {@code WebProjectionSecurityTest} is the end-to-end guard for the same control.
 */
class ReadPolicyGuardTest {

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern POLICED_READ =
            Pattern.compile("\\b(?:from|join)\\s+\"(wqPrjTransaction|wqOwnerMonth)\"", Pattern.CASE_INSENSITIVE);
    /** The policy's sources, compared per row or joined through unnest (as the key-array fetch joins). */
    private static final Pattern SOURCES = Pattern.compile(
            "\"source\"\\s*=\\s*any\\s*\\(cast\\(:sources as smallint\\[\\]\\)\\)"
                    + "|unnest\\(cast\\(:sources as smallint\\[\\]\\)\\) as \"shown\" \\(\"source\"\\) on \"wqPrjTransaction\"\\.\"source\" = \"shown\"\\.\"source\"");
    private static final Pattern ZERO_AMOUNTS = Pattern.compile("cast\\(:includeZeroAmounts as boolean\\)");
    private static final Pattern OWNER_TYPE = Pattern.compile("\"ownerType\"\\s*=\\s*:ownerType");
    private static final Pattern SESSION_PAGE = Pattern.compile("\"wqPrjSession\"\\.\"startedAt\"\\s*>=\\s*cast\\(:lo");
    private static final Pattern SESSION_SOURCES =
            Pattern.compile("\"firstSource\"\\s*=\\s*any\\s*\\(cast\\(:sources as smallint\\[\\]\\)\\)");
    private static final List<String> ENDPOINT_NAMES =
            List.of("wqprjwebtransaction", "wqprjcctransaction", "wqprjtimeline", "\"onweb\"", "\"viewkind\"");

    @Test
    void every_read_of_the_transactions_and_counts_binds_the_read_policy() {
        List<Path> files = sources("wqs/read");
        assertThat(statements(files)).as("read statements found").isGreaterThan(5);
        assertThat(unpoliced(files)).isEmpty();
    }

    @Test
    void no_main_source_names_an_endpoint_relation() {
        assertThat(endpointNames(sources("wqs"))).isEmpty();
    }

    @Test
    void every_read_query_implementation_holds_a_read_policy() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("wqs.read");
        List<String> implementations = new ArrayList<>();
        for (JavaClass c : classes) {
            boolean query = c.isAssignableTo(Level0Query.class) || c.isAssignableTo(HighLevelQuery.class)
                    || c.isAssignableTo(HighLevelTransactionQuery.class);
            if (!query || c.isInterface() || c.getModifiers().contains(JavaModifier.ABSTRACT)) {
                continue;
            }
            implementations.add(c.getSimpleName());
            assertThat(c.getAllFields()).as(c.getName() + " holds its ReadPolicy")
                    .anyMatch(f -> f.getRawType().isEquivalentTo(ReadPolicy.class));
        }
        assertThat(implementations).as("both endpoints, three query kinds").hasSize(6);
    }

    /** The guards can fail: a read missing each predicate, an unpoliced session page, an endpoint view. */
    @Test
    void the_guards_report_what_they_forbid(@TempDir Path dir) throws IOException {
        Path noSources = write(dir, "NoSources.java", """
                select 1 from "wqPrjTransaction" where "ownerType" = :ownerType
                  and (cast(:includeZeroAmounts as boolean) or not "zeroAmount")""");
        Path noZero = write(dir, "NoZero.java", """
                select 1 from "wqOwnerMonth" where "ownerType" = :ownerType
                  and "source" = any (cast(:sources as smallint[]))""");
        Path twoReadsOneFilter = write(dir, "TwoReads.java", """
                select (select count(*) from "wqPrjTransaction" where "ownerType" = :ownerType
                          and "source" = any (cast(:sources as smallint[]))
                          and (cast(:includeZeroAmounts as boolean) or not "zeroAmount"))
                     + (select count(*) from "wqPrjTransaction" where "ownerType" = :ownerType)""");
        Path sessions = write(dir, "Sessions.java", """
                select 1 from "wqPrjSession" where "wqPrjSession"."startedAt" >= cast(:lo as timestamptz)""");
        Path good = write(dir, "Good.java", """
                select 1 from "wqPrjSession" where "wqPrjSession"."firstSource" = any (cast(:sources as smallint[]))
                  and "wqPrjSession"."startedAt" >= cast(:lo as timestamptz)
                union all
                select 1 from "wqPrjTransaction" where "ownerType" = :ownerType
                  and "source" = any (cast(:sources as smallint[]))
                  and (cast(:includeZeroAmounts as boolean) or not "zeroAmount")""");
        Path view = write(dir, "View.java", """
                select 1 from "wqPrjWebTransaction" where "onWeb\"""");

        assertThat(unpoliced(List.of(noSources, noZero, twoReadsOneFilter, sessions, good)))
                .hasSize(4)
                .anyMatch(m -> m.startsWith("NoSources.java"))
                .anyMatch(m -> m.startsWith("NoZero.java"))
                .anyMatch(m -> m.startsWith("TwoReads.java"))
                .anyMatch(m -> m.startsWith("Sessions.java"))
                .noneMatch(m -> m.startsWith("Good.java"));
        assertThat(endpointNames(List.of(view, good))).as("the view and its column")
                .hasSize(2).allMatch(m -> m.startsWith("View.java:2"));
    }

    private static Path write(Path dir, String name, String sql) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "class " + name.replace(".java", "") + " { String sql = \"\"\"\n" + sql + "\n\"\"\"; }");
        return file;
    }

    private static int statements(List<Path> files) {
        int n = 0;
        for (Path file : files) {
            Matcher m = TEXT_BLOCK.matcher(read(file));
            while (m.find()) {
                if (POLICED_READ.matcher(m.group(1)).find()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** {@code file: statement} for every text block that reads a policed table without binding the policy. */
    private static List<String> unpoliced(List<Path> files) {
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            Matcher m = TEXT_BLOCK.matcher(read(file));
            while (m.find()) {
                String sql = m.group(1);
                int reads = count(POLICED_READ, sql);
                boolean policed = count(SOURCES, sql) >= reads && count(ZERO_AMOUNTS, sql) >= reads
                        && count(OWNER_TYPE, sql) >= reads;
                boolean sessionsPoliced = count(SESSION_SOURCES, sql) >= count(SESSION_PAGE, sql);
                if (!policed || !sessionsPoliced) {
                    out.add(file.getFileName() + ": " + sql.strip().replaceAll("\\s+", " "));
                }
            }
        }
        return out;
    }

    /** {@code file:line: text} for every line naming an endpoint relation or column, any case. */
    private static List<String> endpointNames(List<Path> files) {
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = read(file).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).toLowerCase(Locale.ROOT).replace("\\\"", "\"");
                if (line.strip().startsWith("*") || line.strip().startsWith("//")) {
                    continue; // prose may name what was removed
                }
                for (String name : ENDPOINT_NAMES) {
                    if (line.contains(name)) {
                        out.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
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

    /** The main Java sources under a package directory, required to exist (no hollow pass). */
    private static List<Path> sources(String pkg) {
        Path root = Stream.of(Path.of("src/main/java"), Path.of("app/src/main/java"))
                .filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("main sources not found from " + Path.of("").toAbsolutePath()));
        try (Stream<Path> walk = Files.walk(root.resolve(pkg))) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            assertThat(files).as("Java sources under " + pkg).isNotEmpty();
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
