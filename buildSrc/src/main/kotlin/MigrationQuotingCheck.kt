import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

// An identifier-shaped run in whatever's left once every comment, string literal,
// dollar-quoted body and already-quoted identifier has been blanked out.
private val IDENTIFIER_TOKEN = Regex("[A-Za-z_][A-Za-z0-9_]*")

private enum class ScanState { DEFAULT, LINE_COMMENT, BLOCK_COMMENT, SINGLE_QUOTED, DOUBLE_QUOTED, DOLLAR_QUOTED }

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
private fun stripNonSqlRegions(sql: String): String {
    val bare = StringBuilder()
    var state = ScanState.DEFAULT
    var dollarClose = ""
    var i = 0

    while (i < sql.length) {
        when (state) {
            ScanState.DEFAULT -> when {
                sql.startsWith("--", i) -> { state = ScanState.LINE_COMMENT; i += 2 }
                sql.startsWith("/*", i) -> { state = ScanState.BLOCK_COMMENT; i += 2 }
                sql[i] == '\'' -> { state = ScanState.SINGLE_QUOTED; i += 1 }
                sql[i] == '"' -> { state = ScanState.DOUBLE_QUOTED; i += 1 }
                sql[i] == '$' -> {
                    val close = matchDollarQuoteOpen(sql, i)
                    if (close != null) {
                        dollarClose = close
                        state = ScanState.DOLLAR_QUOTED
                        i += close.length
                    } else {
                        bare.append(sql[i]); i += 1
                    }
                }
                else -> { bare.append(sql[i]); i += 1 }
            }

            ScanState.LINE_COMMENT ->
                if (sql[i] == '\n') { bare.append(' '); state = ScanState.DEFAULT } else { i += 1 }

            ScanState.BLOCK_COMMENT ->
                if (sql.startsWith("*/", i)) { bare.append(' '); state = ScanState.DEFAULT; i += 2 } else { i += 1 }

            ScanState.SINGLE_QUOTED -> when {
                sql.startsWith("''", i) -> i += 2 // escaped quote: stays inside the literal
                sql[i] == '\'' -> { bare.append(' '); state = ScanState.DEFAULT; i += 1 }
                else -> i += 1
            }

            ScanState.DOUBLE_QUOTED -> when {
                sql.startsWith("\"\"", i) -> i += 2 // escaped quote: stays inside the identifier
                sql[i] == '"' -> { bare.append(' '); state = ScanState.DEFAULT; i += 1 }
                else -> i += 1
            }

            ScanState.DOLLAR_QUOTED ->
                if (sql.startsWith(dollarClose, i)) {
                    bare.append(' '); state = ScanState.DEFAULT; i += dollarClose.length
                } else {
                    i += 1
                }
        }
    }

    return bare.toString()
}

/** Matches a dollar-quote opening delimiter ($$ or $tag$) starting exactly at `start`, if any. */
private fun matchDollarQuoteOpen(sql: String, start: Int): String? {
    var j = start + 1
    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j += 1
    return if (j < sql.length && sql[j] == '$') sql.substring(start, j + 1) else null
}

/**
 * A token is camelCase when an uppercase letter follows a lowercase one anywhere earlier
 * in the token, letters or digits may sit between them (e.g. "item2Id" still counts: the
 * 'm' is lowercase, the 'I' two characters later is upper, and that's enough).
 */
private fun isCamelCase(token: String): Boolean {
    var sawLowercase = false
    for (c in token) {
        if (c.isUpperCase() && sawLowercase) return true
        if (c.isLowerCase()) sawLowercase = true
    }
    return false
}

/**
 * Finds camelCase identifiers that are NOT double-quoted. Strategy: strip every comment,
 * string literal, dollar-quoted body and already-quoted identifier down to a blank marker,
 * then tokenize what's left and flag any token with a camelCase shape.
 */
fun findUnquotedIdentifiers(sql: String): List<String> {
    val bare = stripNonSqlRegions(sql)

    return IDENTIFIER_TOKEN.findAll(bare)
        .map { it.value }
        .filter { isCamelCase(it) }
        .distinct()
        .toList()
}

abstract class MigrationQuotingCheck : DefaultTask() {

    @get:InputFiles
    abstract val migrations: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val failures = mutableListOf<String>()

        migrations.files.filter { it.extension == "sql" }.sorted().forEach { file: File ->
            val violations = findUnquotedIdentifiers(file.readText())
            if (violations.isNotEmpty()) {
                failures += "${file.name}: unquoted ${violations.joinToString(", ")}"
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "camelCase identifiers must be double-quoted (spec section 3):\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }
}
