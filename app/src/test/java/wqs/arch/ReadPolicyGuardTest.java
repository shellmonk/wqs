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
 *   <li>every SQL literal under {@code wqs/read} -- a text block, or an ordinary string literal
 *       (final-review M3) -- that reads "wqPrjTransaction" or "wqOwnerMonth" binds
 *       the policy's sources ({@code "source" = any (cast(:sources as smallint[]))}, or joined through
 *       {@code unnest(cast(:sources as smallint[]))}) and zero-amount
 *       rule ({@code cast(:includeZeroAmounts as boolean)}) once per read, and the owner type;</li>
 *   <li>every SQL literal that reads "wqPrjSession" or "wqPrjPlay" binds the policy's sources on their
 *       "firstSource" (final-review M4);</li>
 *   <li>an ordinary string literal that names a policed relation other than as a column qualifier
 *       ({@code "wqPrjTransaction".}) is a read, and is checked as one;</li>
 *   <li>no /web class names {@code ReadPolicy.CC} and no /cc class names {@code ReadPolicy.WEB}
 *       (final-review M2; EndpointPolicyWiringTest checks the wired values);</li>
 *   <li>no main source names an endpoint relation or column (the dropped views, the page index,
 *       "onWeb", "viewKind");</li>
 *   <li>every read query implementation holds its endpoint's {@link ReadPolicy} (ArchUnit).</li>
 * </ul>
 * {@code WebProjectionSecurityTest} is the end-to-end guard for the same control.
 */
class ReadPolicyGuardTest {

    private static final Pattern POLICED_READ =
            Pattern.compile("\\b(?:from|join)\\s+\"(wqPrjTransaction|wqOwnerMonth)\"", Pattern.CASE_INSENSITIVE);
    /** The policy's sources, compared per row or joined through unnest (as the key-array fetch joins). */
    private static final Pattern SOURCES = Pattern.compile(
            "\"source\"\\s*=\\s*any\\s*\\(cast\\(:sources as smallint\\[\\]\\)\\)"
                    + "|unnest\\(cast\\(:sources as smallint\\[\\]\\)\\) as \"shown\" \\(\"source\"\\) on \"wqPrjTransaction\"\\.\"source\" = \"shown\"\\.\"source\"");
    private static final Pattern ZERO_AMOUNTS = Pattern.compile("cast\\(:includeZeroAmounts as boolean\\)");
    private static final Pattern OWNER_TYPE = Pattern.compile("\"ownerType\"\\s*=\\s*:ownerType");
    private static final Pattern SESSION_READ =
            Pattern.compile("\\b(?:from|join)\\s+\"(wqPrjSession|wqPrjPlay)\"", Pattern.CASE_INSENSITIVE);
    /** A policed relation named in an ordinary literal, not as a column qualifier ({@code "wqPrjTransaction".}). */
    private static final Pattern BARE_RELATION =
            Pattern.compile("\"(wqPrjTransaction|wqOwnerMonth|wqPrjSession|wqPrjPlay)\"(?!\\.)");
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

    /** Final-review M2: a /web class never names /cc's policy, nor a /cc class /web's. */
    @Test
    void each_endpoint_names_only_its_own_policy() {
        List<Path> web = new ArrayList<>(sources("wqs/read/web"));
        web.addAll(sources("wqs/api/web"));
        List<Path> cc = new ArrayList<>(sources("wqs/read/cc"));
        cc.addAll(sources("wqs/api/cc"));
        assertThat(naming(web, "ReadPolicy.CC")).as("/web naming /cc's policy").isEmpty();
        assertThat(naming(cc, "ReadPolicy.WEB")).as("/cc naming /web's policy").isEmpty();
        assertThat(naming(sources("wqs/read/web"), "ReadPolicy.WEB")).as("each /web query names its policy").hasSize(3);
        assertThat(naming(sources("wqs/read/cc"), "ReadPolicy.CC")).as("each /cc query names its policy").hasSize(3);
    }

    /**
     * Final-review M3: the guard reads ordinary string literals too -- a whole statement in one, a
     * statement concatenated from several -- while a column-qualifier fragment is no read.
     */
    @Test
    void the_guard_reads_ordinary_string_literals(@TempDir Path dir) throws IOException {
        Path plain = writeJava(dir, "Plain.java",
                "String sql = \"select 1 from \\\"wqPrjTransaction\\\" where \\\"userId\\\" = :userId\";");
        Path concatenated = writeJava(dir, "Concat.java",
                "String sql = \"select 1 from \" + \"\\\"wqOwnerMonth\\\"\" + \" where \\\"userId\\\" = :userId\";");
        Path session = writeJava(dir, "SessionByKey.java",
                "String sql = \"select 1 from \\\"wqPrjSession\\\" where \\\"batchId\\\" = :id\";");
        Path fragment = writeJava(dir, "Fragment.java",
                "String f = \" and not (\\\"wqPrjTransaction\\\".\\\"cancelled\\\")\";\n"
                        + "// select 1 from \"wqPrjTransaction\" in a comment is prose\n"
                        + "char q = '\"';");
        Path policed = writeJava(dir, "Policed.java",
                "String sql = \"select 1 from \\\"wqPrjTransaction\\\" where \\\"ownerType\\\" = :ownerType\"\n"
                        + "  + \" and \\\"source\\\" = any (cast(:sources as smallint[]))\"\n"
                        + "  + \" and (cast(:includeZeroAmounts as boolean) or not \\\"zeroAmount\\\")\";");

        assertThat(unpoliced(List.of(plain, concatenated, session, fragment)))
                .hasSize(3)
                .anyMatch(m -> m.startsWith("Plain.java"))
                .anyMatch(m -> m.startsWith("Concat.java"))
                .anyMatch(m -> m.startsWith("SessionByKey.java"));
        assertThat(unpoliced(List.of(policed))).as("split over a concatenation: each piece is checked").hasSize(1);
    }

    /** The M2 check can fail: a /web class naming /cc's policy is reported. */
    @Test
    void the_policy_naming_check_reports_a_crossed_policy(@TempDir Path dir) throws IOException {
        Path crossed = writeJava(dir, "WebCrossed.java", "static final Object POLICY = ReadPolicy.CC;");
        assertThat(naming(List.of(crossed), "ReadPolicy.CC")).singleElement().asString().startsWith("WebCrossed.java:1");
    }

    private static Path writeJava(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "class " + name.replace(".java", "") + " { " + body + " }");
        return file;
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
            for (Literal literal : literals(read(file))) {
                if (POLICED_READ.matcher(literal.text()).find()) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * {@code file: statement} for every SQL literal -- text block or ordinary string literal -- that
     * reads a policed table without binding the policy, or a session or play without binding the
     * policy's sources on "firstSource". An ordinary literal naming a relation (not as a column
     * qualifier) is a read even without its "from": a statement split over a concatenation is
     * checked piece by piece, so the piece naming the table must carry the predicates itself.
     */
    private static List<String> unpoliced(List<Path> files) {
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            for (Literal literal : literals(read(file))) {
                String sql = literal.text();
                int reads = count(POLICED_READ, sql);
                int sessionReads = count(SESSION_READ, sql);
                Matcher bare = BARE_RELATION.matcher(sql);
                while (bare.find()) {
                    boolean session = bare.group(1).equals("wqPrjSession") || bare.group(1).equals("wqPrjPlay");
                    if (session) {
                        sessionReads = Math.max(sessionReads, 1);
                    } else {
                        reads = Math.max(reads, 1);
                    }
                }
                boolean policed = count(SOURCES, sql) >= reads && count(ZERO_AMOUNTS, sql) >= reads
                        && count(OWNER_TYPE, sql) >= reads;
                boolean sessionsPoliced = count(SESSION_SOURCES, sql) >= sessionReads;
                if (!policed || !sessionsPoliced) {
                    out.add(file.getFileName() + ": " + sql.strip().replaceAll("\\s+", " "));
                }
            }
        }
        return out;
    }

    /** A string literal of a Java source: a text block or an ordinary literal, its quotes unescaped. */
    record Literal(String text, boolean textBlock) {}

    /**
     * The string literals of a Java source, in order. Comments and char literals are skipped, so a
     * table named in prose is not a read; a literal's escaped quotes are unescaped, so
     * {@code "from \"wqPrjTransaction\""} reads as the SQL it sends.
     */
    static List<Literal> literals(String src) {
        List<Literal> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                i = closing(src, i + 1, "'") + 1;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = closing(src, i + 3, "\"\"\"");
                out.add(new Literal(unescape(src.substring(i + 3, Math.min(end, n))), true));
                i = end + 3;
            } else if (c == '"') {
                int end = closing(src, i + 1, "\"");
                out.add(new Literal(unescape(src.substring(i + 1, Math.min(end, n))), false));
                i = end + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** The index of the unescaped {@code delimiter} closing a literal opened before {@code from}. */
    private static int closing(String src, int from, String delimiter) {
        int j = from;
        while (j < src.length() && !src.startsWith(delimiter, j)) {
            j += src.charAt(j) == '\\' ? 2 : 1;
        }
        return j;
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** {@code file:line: text} for every line of {@code files} naming {@code forbidden}, comments aside. */
    private static List<String> naming(List<Path> files, String forbidden) {
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = read(file).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                    continue;
                }
                if (line.contains(forbidden)) {
                    out.add(file.getFileName() + ":" + (i + 1) + ": " + line);
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
