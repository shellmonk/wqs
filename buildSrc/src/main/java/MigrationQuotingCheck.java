import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;

public abstract class MigrationQuotingCheck extends DefaultTask {

    // An identifier-shaped run in whatever's left once every comment, string literal,
    // dollar-quoted body and already-quoted identifier has been blanked out.
    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private enum ScanState { DEFAULT, LINE_COMMENT, BLOCK_COMMENT, SINGLE_QUOTED, DOUBLE_QUOTED, DOLLAR_QUOTED }

    /**
     * Strips every line comment, block comment, single-quoted string literal (with ''
     * escaping), double-quoted identifier (with "" escaping) and dollar-quoted body
     * ($tag$...$tag$) down to a single blank marker, leaving only bare, unquoted SQL text.
     *
     * This has to be one stateful pass rather than a sequence of independent regexes: a `--`
     * inside a string literal is not a comment, and a `'` inside a comment does not open a
     * string. Which construct is "live" at a given position depends entirely on what construct
     * is already open, and only a single scan that tracks that can get every position right.
     */
    private static String stripNonSqlRegions(String sql) {
        StringBuilder bare = new StringBuilder();
        ScanState state = ScanState.DEFAULT;
        String dollarClose = "";
        int i = 0;

        while (i < sql.length()) {
            char c = sql.charAt(i);
            switch (state) {
                case DEFAULT -> {
                    if (sql.startsWith("--", i)) {
                        state = ScanState.LINE_COMMENT;
                        i += 2;
                    } else if (sql.startsWith("/*", i)) {
                        state = ScanState.BLOCK_COMMENT;
                        i += 2;
                    } else if (c == '\'') {
                        state = ScanState.SINGLE_QUOTED;
                        i += 1;
                    } else if (c == '"') {
                        state = ScanState.DOUBLE_QUOTED;
                        i += 1;
                    } else if (c == '$') {
                        String close = matchDollarQuoteOpen(sql, i);
                        if (close != null) {
                            dollarClose = close;
                            state = ScanState.DOLLAR_QUOTED;
                            i += close.length();
                        } else {
                            bare.append(c);
                            i += 1;
                        }
                    } else {
                        bare.append(c);
                        i += 1;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        bare.append(' ');
                        state = ScanState.DEFAULT;
                    } else {
                        i += 1;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (sql.startsWith("*/", i)) {
                        bare.append(' ');
                        state = ScanState.DEFAULT;
                        i += 2;
                    } else {
                        i += 1;
                    }
                }
                case SINGLE_QUOTED -> {
                    if (sql.startsWith("''", i)) {
                        i += 2; // escaped quote: stays inside the literal
                    } else if (c == '\'') {
                        bare.append(' ');
                        state = ScanState.DEFAULT;
                        i += 1;
                    } else {
                        i += 1;
                    }
                }
                case DOUBLE_QUOTED -> {
                    if (sql.startsWith("\"\"", i)) {
                        i += 2; // escaped quote: stays inside the identifier
                    } else if (c == '"') {
                        bare.append(' ');
                        state = ScanState.DEFAULT;
                        i += 1;
                    } else {
                        i += 1;
                    }
                }
                case DOLLAR_QUOTED -> {
                    if (sql.startsWith(dollarClose, i)) {
                        bare.append(' ');
                        state = ScanState.DEFAULT;
                        i += dollarClose.length();
                    } else {
                        i += 1;
                    }
                }
            }
        }

        return bare.toString();
    }

    /** Matches a dollar-quote opening delimiter ($$ or $tag$) starting exactly at `start`, if any. */
    private static String matchDollarQuoteOpen(String sql, int start) {
        int j = start + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j += 1;
        return (j < sql.length() && sql.charAt(j) == '$') ? sql.substring(start, j + 1) : null;
    }

    /**
     * A token is camelCase when an uppercase letter follows a lowercase one anywhere earlier
     * in the token, letters or digits may sit between them (e.g. "item2Id" still counts: the
     * 'm' is lowercase, the 'I' two characters later is upper, and that's enough).
     */
    private static boolean isCamelCase(String token) {
        boolean sawLowercase = false;
        for (int k = 0; k < token.length(); k++) {
            char c = token.charAt(k);
            if (Character.isUpperCase(c) && sawLowercase) return true;
            if (Character.isLowerCase(c)) sawLowercase = true;
        }
        return false;
    }

    /**
     * Finds camelCase identifiers that are NOT double-quoted. Strategy: strip every comment,
     * string literal, dollar-quoted body and already-quoted identifier down to a blank marker,
     * then tokenize what's left and flag any token with a camelCase shape.
     */
    public static List<String> findUnquotedIdentifiers(String sql) {
        String bare = stripNonSqlRegions(sql);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = IDENTIFIER_TOKEN.matcher(bare);
        while (m.find()) {
            String token = m.group();
            if (isCamelCase(token)) found.add(token);
        }
        return new ArrayList<>(found);
    }

    @InputFiles
    public abstract ConfigurableFileCollection getMigrations();

    @TaskAction
    public void check() throws IOException {
        List<String> failures = new ArrayList<>();

        List<File> files = getMigrations().getFiles().stream()
                .filter(f -> f.getName().endsWith(".sql"))
                .sorted(Comparator.naturalOrder())
                .toList();
        for (File file : files) {
            List<String> violations = findUnquotedIdentifiers(Files.readString(file.toPath()));
            if (!violations.isEmpty()) {
                failures.add(file.getName() + ": unquoted " + String.join(", ", violations));
            }
        }

        if (!failures.isEmpty()) {
            throw new GradleException(
                    "camelCase identifiers must be double-quoted (spec section 3):\n"
                            + failures.stream().map(f -> "  - " + f).collect(Collectors.joining("\n")));
        }
    }
}
